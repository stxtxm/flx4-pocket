package io.github.timotheegrollier.flx4pocket

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.util.UUID

data class Track(
    val uri: Uri,
    val name: String,
    val lastModified: Long,
    /** Where it was found: "Music/House" under an added folder, or [Library.ADDED_FILES]. */
    val folder: String = "",
    /** The added folder tree, or the added file itself, that brought it into the library. */
    val source: Uri? = null
)

/**
 * A row of the folder column: [path] selects the tracks under it ("" = all), [depth]
 * indents it; a [playlist] row shows that playlist instead.
 */
data class FolderRow(val path: String, val label: String, val depth: Int, val count: Int, val playlist: String? = null)

/**
 * Reads music folders and single files through the Storage Access Framework,
 * so the app needs no storage permission and works the same on a Pixel 9a and
 * on older devices.
 */
class Library(private val context: Context) {

    /** Tracks of every folder tree in [folders] and every file in [files], sorted by name. */
    fun scan(folders: Collection<Uri>, files: Collection<Uri>): List<Track> {
        val result = ArrayList<Track>()
        val roots = HashSet<String>()
        for (tree in folders) {
            var root = rootName(tree)
            // Two added folders with the same name stay apart in the folder column.
            var n = 2
            while (!roots.add(root)) root = "${rootName(tree)} ${n++}"
            scanTree(tree, root, result)
        }
        for (file in files) {
            try {
                context.contentResolver.query(file, FILE_COLUMNS, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val name = cursor.getString(0) ?: "Track"
                        val modified = if (cursor.isNull(1)) 0L else cursor.getLong(1)
                        result.add(Track(file, name, modified, ADDED_FILES, file))
                    }
                }
            } catch (_: Exception) {
                // Deleted or no longer readable: it just drops out of the list.
            }
        }
        return result.sortedBy { it.name.lowercase() }
    }

    private fun rootName(tree: Uri): String = try {
        val id = DocumentsContract.getTreeDocumentId(tree)
        context.contentResolver.query(
            DocumentsContract.buildDocumentUriUsingTree(tree, id), FILE_COLUMNS, null, null, null
        )?.use { if (it.moveToFirst()) it.getString(0) else null } ?: id.substringAfterLast(':')
    } catch (_: Exception) {
        "Music"
    }

    private fun scanTree(treeUri: Uri, root: String, result: MutableList<Track>, limit: Int = 3000) {
        val queue = ArrayDeque<Pair<String, String>>() // document id, folder path
        val start = result.size
        try {
            queue.add(DocumentsContract.getTreeDocumentId(treeUri) to root)
        } catch (e: Exception) {
            return
        }
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )

        while (queue.isNotEmpty() && result.size - start < limit) {
            val (documentId, path) = queue.removeFirst()
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
                    while (cursor.moveToNext() && result.size - start < limit) {
                        val id = cursor.getString(idIndex) ?: continue
                        val name = cursor.getString(nameIndex) ?: "Track"
                        val mime = cursor.getString(mimeIndex) ?: ""
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            queue.add(id to "$path/$name")
                            continue
                        }
                        if (!isAudio(name, mime)) continue
                        val modified = if (modifiedIndex >= 0) cursor.getLong(modifiedIndex) else 0L
                        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                        result.add(Track(uri, name, modified, path, treeUri))
                    }
                }
            } catch (e: Exception) {
                // A provider can reject a subtree; keep the rest of the scan.
            }
        }
    }

    private fun isAudio(name: String, mime: String): Boolean {
        if (mime.startsWith("audio/")) return true
        val extension = name.substringAfterLast('.', "").lowercase()
        return extension in AUDIO_EXTENSIONS
    }

    companion object {
        /** Folder of the tracks added one by one. */
        const val ADDED_FILES = "Added tracks"

        private val FILE_COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        private val AUDIO_EXTENSIONS = setOf(
            "mp3", "m4a", "aac", "wav", "flac", "ogg", "oga", "opus", "aiff", "aif", "wma"
        )

        /**
         * The folder column: "all" first, then every folder holding tracks and its
         * parents, in tree order, each with the number of tracks under it.
         */
        fun folderRows(trackFolders: List<String>, allLabel: String): List<FolderRow> {
            val counts = java.util.TreeMap<String, Int>(TREE_ORDER)
            for (folder in trackFolders) {
                var path = folder
                while (path.isNotEmpty()) {
                    counts[path] = (counts[path] ?: 0) + 1
                    path = path.substringBeforeLast('/', "")
                }
            }
            return listOf(FolderRow("", allLabel, 0, trackFolders.size)) + counts.map { (path, count) ->
                FolderRow(path, path.substringAfterLast('/'), path.count { it == '/' }, count)
            }
        }

        /** True when a track in [folder] belongs under the folder row [path]. */
        fun inFolder(folder: String, path: String): Boolean =
            path.isEmpty() || folder == path || folder.startsWith("$path/")

        /** Parents before children, siblings alphabetically (ignoring case). */
        private val TREE_ORDER = Comparator<String> { a, b ->
            val x = a.split('/')
            val y = b.split('/')
            for (i in 0 until minOf(x.size, y.size)) {
                val c = x[i].compareTo(y[i], ignoreCase = true).takeIf { it != 0 } ?: x[i].compareTo(y[i])
                if (c != 0) return@Comparator c
            }
            x.size - y.size
        }
    }
}

/**
 * Persists what the analysis of a track produced, keyed by document and
 * modification time: the beat grid and hot cues in SharedPreferences, the
 * waveform as a file in the cache directory.
 */
class AnalysisCache(context: Context) {

    private val preferences =
        context.getSharedPreferences("flx4-analysis", Context.MODE_PRIVATE)
    private val waveforms = File(context.cacheDir, "waveforms")

    fun get(track: Track): BpmAnalyzer.Result? {
        val parts = preferences.getString(key(track), null)?.split('|') ?: return null
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

    fun waveformFile(track: Track): File =
        File(waveforms, UUID.nameUUIDFromBytes(key(track).toByteArray()).toString())

    fun hotCues(track: Track): Array<Double?> {
        val parts = preferences.getString(key(track) + "|cues", null)?.split(';')
        return Array(8) { parts?.getOrNull(it)?.toDoubleOrNull() }
    }

    fun putHotCues(track: Track, cues: Array<Double?>) {
        preferences.edit()
            .putString(key(track) + "|cues", cues.joinToString(";") { it?.toString().orEmpty() })
            .apply()
    }

    /** Memory cues as "in:out" pairs, out < 0 for a point. */
    fun memoryCues(track: Track): List<Deck.MemoryCue> =
        preferences.getString(key(track) + "|memory", null).orEmpty().split(';').mapNotNull { pair ->
            val parts = pair.split(':')
            val start = parts.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
            Deck.MemoryCue(start, parts.getOrNull(1)?.toDoubleOrNull() ?: -1.0)
        }

    fun putMemoryCues(track: Track, cues: List<Deck.MemoryCue>) {
        preferences.edit()
            .putString(key(track) + "|memory", cues.joinToString(";") { "${it.inSec}:${it.outSec}" })
            .apply()
    }

    private fun key(track: Track): String = "${track.uri}|${track.lastModified}"
}

/** One decode pass over a track that yields both its beat grid and its waveform. */
object TrackAnalyzer {

    /**
     * Blocking; run it on a worker thread. [onWaveform] receives the waveform
     * as soon as it exists (it keeps filling while the UI draws it), [onGrid]
     * the beat grid once the first minute has been heard. Both may be called
     * immediately from the cache.
     */
    fun analyze(
        context: Context,
        track: Track,
        cache: AnalysisCache,
        cancelled: () -> Boolean,
        onWaveform: (Waveform) -> Unit,
        onGrid: (BpmAnalyzer.Result) -> Unit
    ) {
        val cachedGrid = cache.get(track)?.also(onGrid)
        val cachedWave = Waveform.read(cache.waveformFile(track))?.also(onWaveform)
        if (cachedGrid != null && cachedWave != null) return

        var builder: WaveformBuilder? = null
        var onsets: BpmAnalyzer.Onsets? = null
        var gridDone = cachedGrid != null
        var duration = 0.0
        fun finishGrid() {
            gridDone = true
            val estimate = onsets?.estimate() ?: return
            val result = BpmAnalyzer.Result(estimate.bpm, estimate.anchorSec, estimate.confidence, duration)
            cache.put(track, result)
            onGrid(result)
        }

        val decoded = PcmDecoder.decode(context, track.uri) { stereo, frames, sampleRate, durationSec ->
            duration = durationSec
            if (cachedWave == null) {
                val target = builder ?: WaveformBuilder(sampleRate, durationSec).also {
                    builder = it
                    onWaveform(it.waveform)
                }
                target.add(stereo, frames)
            }
            if (!gridDone) {
                val energy = onsets ?: BpmAnalyzer.Onsets(sampleRate).also { onsets = it }
                energy.add(stereo, frames)
                if (energy.seconds >= GRID_SECONDS) finishGrid()
            }
            !cancelled() && (cachedWave == null || !gridDone)
        }
        if (cancelled()) return
        if (!gridDone) finishGrid()
        val waveform = builder?.waveform ?: return
        waveform.complete = true
        if (decoded) runCatching { waveform.write(cache.waveformFile(track)) }
    }

    private const val GRID_SECONDS = 60.0
}
