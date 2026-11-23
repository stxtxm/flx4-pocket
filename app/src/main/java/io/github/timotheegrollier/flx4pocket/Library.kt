package io.github.timotheegrollier.flx4pocket

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

data class Track(
    val uri: Uri,
    val name: String,
    val lastModified: Long
)

/**
 * Reads a music folder through the Storage Access Framework so the app needs no
 * storage permission and works the same on a Pixel 9a and on older devices.
 */
class Library(private val context: Context) {

    fun scan(treeUri: Uri, limit: Int = 3000): List<Track> {
        val result = ArrayList<Track>()
        val queue = ArrayDeque<String>()
        val root = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: Exception) {
            return result
        }
        queue.add(root)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )

        while (queue.isNotEmpty() && result.size < limit) {
            val documentId = queue.removeFirst()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri,
                documentId
            )
            try {
                context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID
                    )
                    val nameIndex = cursor.getColumnIndexOrThrow(
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME
                    )
                    val mimeIndex = cursor.getColumnIndexOrThrow(
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                    )
                    val modifiedIndex = cursor.getColumnIndex(
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED
                    )
                    while (cursor.moveToNext() && result.size < limit) {
                        val id = cursor.getString(idIndex) ?: continue
                        val name = cursor.getString(nameIndex) ?: "Track"
                        val mime = cursor.getString(mimeIndex) ?: ""
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            queue.add(id)
                            continue
                        }
                        if (!isAudio(name, mime)) continue
                        val modified = if (modifiedIndex >= 0) cursor.getLong(modifiedIndex) else 0L
                        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                        result.add(Track(uri, name, modified))
                    }
                }
            } catch (e: Exception) {
                // A provider can reject a subtree; keep the rest of the scan.
            }
        }

        return result.sortedWith(
            compareBy<Track> { it.name.lowercase() }
        )
    }

    private fun isAudio(name: String, mime: String): Boolean {
        if (mime.startsWith("audio/")) return true
        val extension = name.substringAfterLast('.', "").lowercase()
        return extension in AUDIO_EXTENSIONS
    }

    companion object {
        private val AUDIO_EXTENSIONS = setOf(
            "mp3", "m4a", "aac", "wav", "flac", "ogg", "oga", "opus", "aiff", "aif", "wma"
        )
    }
}

/**
 * Persists BPM results keyed by document and modification time. Re-analysing a
 * 60 second window every time a track is loaded would make the app feel slow.
 */
class AnalysisCache(context: Context) {

    private val preferences =
        context.getSharedPreferences("flx4-analysis", Context.MODE_PRIVATE)

    fun get(track: Track): BpmAnalyzer.Result? {
        val raw = preferences.getString(key(track), null) ?: return null
        val parts = raw.split('|')
        if (parts.size != 4) return null
        val bpm = parts[0].toDoubleOrNull() ?: return null
        val anchor = parts[1].toDoubleOrNull() ?: return null
        val confidence = parts[2].toDoubleOrNull() ?: return null
        val duration = parts[3].toDoubleOrNull() ?: return null
        if (bpm <= 0.0) return null
        return BpmAnalyzer.Result(bpm, anchor, confidence, duration)
    }

    fun put(track: Track, result: BpmAnalyzer.Result) {
        preferences.edit()
            .putString(
                key(track),
                "${result.bpm}|${result.anchorSec}|${result.confidence}|${result.durationSec}"
            )
            .apply()
    }

    private fun key(track: Track): String = "${track.uri}|${track.lastModified}"
}
