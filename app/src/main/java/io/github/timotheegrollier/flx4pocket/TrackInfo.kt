package io.github.timotheegrollier.flx4pocket

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.util.Locale

/** What a file says about itself: its tags and its audio format. */
data class TrackInfo(
    val title: String,
    val artist: String,
    val album: String,
    val genre: String,
    val durationSec: Double,
    val codec: String,
    val sampleRate: Int,
    val channels: Int,
    val bitrateKbps: Int
) {
    /** "FLAC · 44.1 kHz · stereo · 1411 kbps", the line a DJ reads to tell files apart. */
    val format: String
        get() = listOf(
            codec,
            if (sampleRate > 0) String.format(Locale.US, "%.1f kHz", sampleRate / 1000.0) else "",
            when (channels) {
                0 -> ""
                1 -> "mono"
                2 -> "stereo"
                else -> "$channels ch"
            },
            if (bitrateKbps > 0) "$bitrateKbps kbps" else ""
        ).filter { it.isNotEmpty() }.joinToString(" · ")

    fun encode(): String = listOf(
        title, artist, album, genre, durationSec, codec, sampleRate, channels, bitrateKbps
    ).joinToString(SEPARATOR) { it.toString().replace(SEPARATOR, " ") }

    companion object {
        private const val SEPARATOR = "\u001F"

        fun decode(text: String): TrackInfo? {
            val parts = text.split(SEPARATOR)
            if (parts.size != 9) return null
            return TrackInfo(
                title = parts[0],
                artist = parts[1],
                album = parts[2],
                genre = parts[3],
                durationSec = parts[4].toDoubleOrNull() ?: return null,
                codec = parts[5],
                sampleRate = parts[6].toIntOrNull() ?: return null,
                channels = parts[7].toIntOrNull() ?: return null,
                bitrateKbps = parts[8].toIntOrNull() ?: return null
            )
        }

        /**
         * Reads the tags and the format of [uri]. Blocking, but it reads only the
         * headers; a file that cannot be read gives its file name as the title.
         */
        fun read(context: Context, uri: Uri, fileName: String): TrackInfo {
            val fallbackTitle = fileName.substringBeforeLast('.').ifBlank { fileName }
            var title = ""
            var artist = ""
            var album = ""
            var genre = ""
            var duration = 0.0
            var bitrate = 0
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty().trim()
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty().trim()
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty().trim()
                genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE).orEmpty().trim()
                duration = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L) / 1000.0
                bitrate = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                    ?.toIntOrNull() ?: 0) / 1000
            } catch (_: Exception) {
                // Tags are optional; the file name is a fine title.
            } finally {
                runCatching { retriever.release() }
            }

            var codec = ""
            var sampleRate = 0
            var channels = 0
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, uri, null)
                val format = PcmDecoder.selectAudioTrack(extractor)
                if (format != null) {
                    codec = codecName(format.getString(MediaFormat.KEY_MIME).orEmpty(), fileName)
                    sampleRate = format.intOr(MediaFormat.KEY_SAMPLE_RATE, 0)
                    channels = format.intOr(MediaFormat.KEY_CHANNEL_COUNT, 0)
                    if (duration <= 0.0) duration = format.longOr(MediaFormat.KEY_DURATION, 0L) / 1_000_000.0
                }
            } catch (_: Exception) {
                // No readable audio track: the tags alone still describe the file.
            } finally {
                runCatching { extractor.release() }
            }

            return TrackInfo(
                title = title.ifBlank { fallbackTitle },
                artist = artist,
                album = album,
                genre = genre,
                durationSec = duration,
                codec = codec,
                sampleRate = sampleRate,
                channels = channels,
                bitrateKbps = bitrate
            )
        }

        /** A short name for a codec MIME type; uncompressed audio is named by its container. */
        fun codecName(mime: String, fileName: String): String = when (mime) {
            "audio/mpeg" -> "MP3"
            "audio/mp4a-latm" -> "AAC"
            "audio/flac" -> "FLAC"
            "audio/opus" -> "Opus"
            "audio/vorbis" -> "Vorbis"
            "audio/ac3" -> "AC-3"
            "audio/x-ms-wma" -> "WMA"
            "audio/raw" -> fileName.substringAfterLast('.', "").uppercase(Locale.US).ifEmpty { "PCM" }
            else -> mime.substringAfter('/', "").uppercase(Locale.US)
        }
    }
}
