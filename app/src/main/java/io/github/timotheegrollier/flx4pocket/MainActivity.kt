package io.github.timotheegrollier.flx4pocket

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicIntegerArray
import kotlin.math.pow
import kotlin.math.roundToInt

class MainActivity : Activity(), Flx4Midi.Listener, DeckPanel.Callbacks {

    private lateinit var engine: AudioEngine
    private lateinit var midi: Flx4Midi
    private lateinit var library: Library
    private lateinit var cache: AnalysisCache
    private lateinit var work: ExecutorService
    private lateinit var libraryAdapter: TrackAdapter
    private val ui = Handler(Looper.getMainLooper())
    private val deckPanels = ArrayList<DeckPanel>(2)

    private lateinit var statusLabel: TextView
    private lateinit var updateButton: Button
    private lateinit var libraryPanel: LinearLayout
    private lateinit var libraryList: ListView
    private lateinit var libraryPathLabel: TextView
    private lateinit var libraryTargetButton: Button
    private lateinit var vu: VuView
    private lateinit var mixerCross: SeekBar
    private lateinit var mixerHeadphone: SeekBar
    private lateinit var mixerMaster: SeekBar
    private lateinit var mixerSplit: ToggleButton
    private lateinit var cueAButton: Button
    private lateinit var cueBButton: Button
    private lateinit var masterLabel: TextView

    private val tracks = ArrayList<Track>()
    private var selectedTrack = -1
    private var libraryTarget = 0
    private var folderUri: Uri? = null
    private val loadTokens = AtomicIntegerArray(2)
    private val loopPads = intArrayOf(-1, -1)
    private val taps = Array(2) { ArrayList<Long>() }
    private var midiName: String? = null
    private var updateInfo: UpdateChecker.UpdateInfo? = null
    private var updateCheckRunning = false
    private var lastUpdateCheckMillis = 0L
    @Volatile
    private var downloadCancelled = false
    private var activeUpdateDialog: AlertDialog? = null
    private var pendingUpdateInfo: UpdateChecker.UpdateInfo? = null

    private val preferences by lazy {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
        ) {
            engine.decks.forEach { it.pause() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeControlStream = AudioManager.STREAM_MUSIC

        engine = AudioEngine(this)
        engine.start()

        setContentView(R.layout.activity_main)
        statusLabel = findViewById(R.id.status)
        updateButton = findViewById(R.id.btn_update)
        vu = findViewById(R.id.mixer_vu)
        mixerCross = findViewById(R.id.mixer_crossfader)
        mixerHeadphone = findViewById(R.id.mixer_headphone)
        mixerMaster = findViewById(R.id.mixer_master)
        mixerSplit = findViewById(R.id.mixer_split)
        cueAButton = findViewById(R.id.mixer_cue_a)
        cueBButton = findViewById(R.id.mixer_cue_b)
        masterLabel = findViewById(R.id.mixer_master_label)

        deckPanels.add(findViewById(R.id.deck_a))
        deckPanels.add(findViewById(R.id.deck_b))
        deckPanels[0].bind(0, getColor(R.color.accent_a))
        deckPanels[1].bind(1, getColor(R.color.accent_b))
        deckPanels.forEach { it.callbacks = this }

        setupMixer()
        setupLibrary()
        setupUpdateButton()
        findViewById<Button>(R.id.btn_library).setOnClickListener {
            showLibrary(libraryPanel.visibility != View.VISIBLE)
        }
        findViewById<Button>(R.id.btn_audio).setOnClickListener { showAudioOutputs() }

        library = Library(this)
        cache = AnalysisCache(this)
        work = Executors.newFixedThreadPool(3)
        libraryAdapter = TrackAdapter()
        libraryList.adapter = libraryAdapter

        engine.splitCue = preferences.getBoolean(PREF_SPLIT, false)
        mixerSplit.isChecked = engine.splitCue
        lastUpdateCheckMillis = preferences.getLong(PREF_LAST_UPDATE_CHECK, 0L)

        val savedFolder = preferences.getString(PREF_FOLDER, null)
        if (savedFolder != null) {
            folderUri = Uri.parse(savedFolder)
            scanFolder(folderUri!!)
        } else {
            libraryPathLabel.text = "Choose a music folder"
            showLibrary(true)
        }

        midi = Flx4Midi(this)
        midi.setListener(this)
        midi.start()

        requestAudioFocus()
        ui.post(refreshRunnable)
        ui.postDelayed({ checkForUpdates(false) }, 4000)
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= 26) {
            val pending = pendingUpdateInfo
            if (pending != null && packageManager.canRequestPackageInstalls()) {
                pendingUpdateInfo = null
                beginUpdate(pending)
            }
        }
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        activeUpdateDialog?.dismiss()
        activeUpdateDialog = null
        if (::midi.isInitialized) midi.close()
        if (::engine.isInitialized) engine.close()
        if (::work.isInitialized) work.shutdownNow()
        abandonAudioFocus()
        super.onDestroy()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (::libraryPanel.isInitialized && libraryPanel.visibility == View.VISIBLE) {
            showLibrary(false)
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    // ---------------------------------------------------------------------
    // UI setup
    // ---------------------------------------------------------------------

    private fun setupMixer() {
        addUnitSlider(mixerCross) { engine.crossfader = it }
        addUnitSlider(mixerHeadphone) { engine.headphoneMix = it }
        addUnitSlider(mixerMaster) { engine.masterGain = it * it }
        mixerSplit.setOnCheckedChangeListener { _, checked ->
            engine.splitCue = checked
            preferences.edit().putBoolean(PREF_SPLIT, checked).apply()
        }
        cueAButton.setOnClickListener {
            engine.decks[0].cueEnabled = !engine.decks[0].cueEnabled
        }
        cueBButton.setOnClickListener {
            engine.decks[1].cueEnabled = !engine.decks[1].cueEnabled
        }
    }

    private fun setupLibrary() {
        libraryPanel = findViewById(R.id.library_panel)
        libraryList = findViewById(R.id.library_list)
        libraryPathLabel = findViewById(R.id.library_path)
        libraryTargetButton = findViewById(R.id.library_target)

        libraryTargetButton.setOnClickListener {
            libraryTarget = 1 - libraryTarget
            libraryTargetButton.text = if (libraryTarget == 0) "TARGET A" else "TARGET B"
        }
        findViewById<Button>(R.id.library_folder).setOnClickListener { chooseFolder() }
        findViewById<Button>(R.id.library_close).setOnClickListener { showLibrary(false) }
        findViewById<Button>(R.id.library_load_a).setOnClickListener { loadSelected(0) }
        findViewById<Button>(R.id.library_load_b).setOnClickListener { loadSelected(1) }
        libraryList.setOnItemClickListener { _, _, position, _ ->
            selectedTrack = position
            libraryAdapter.notifyDataSetChanged()
            tracks.getOrNull(position)?.let { loadTrack(libraryTarget, it) }
        }
    }

    private fun setupUpdateButton() {
        updateButton.setOnClickListener {
            val info = updateInfo
            if (info != null) showUpdateDialog(info) else checkForUpdates(true)
        }
    }

    private fun requestAudioFocus() {
        @Suppress("DEPRECATION")
        val manager = getSystemService(AudioManager::class.java)
        manager.requestAudioFocus(
            focusListener,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN
        )
    }

    private fun abandonAudioFocus() {
        @Suppress("DEPRECATION")
        val manager = getSystemService(AudioManager::class.java)
        manager.abandonAudioFocus(focusListener)
    }

    // ---------------------------------------------------------------------
    // Periodic UI refresh and MIDI LEDs
    // ---------------------------------------------------------------------

    private val refreshRunnable = object : Runnable {
        override fun run() {
            deckPanels[0].render(engine.decks[0], engine.masterDeckIndex)
            deckPanels[1].render(engine.decks[1], engine.masterDeckIndex)
            vu.setLevels(engine.meterL, engine.meterR)
            if (!mixerCross.isPressed) {
                mixerCross.progress = (engine.crossfader * 1000f).roundToInt()
            }
            if (!mixerHeadphone.isPressed) {
                mixerHeadphone.progress = (engine.headphoneMix * 1000f).roundToInt()
            }
            if (!mixerMaster.isPressed) {
                mixerMaster.progress = (kotlin.math.sqrt(engine.masterGain) * 1000f)
                    .roundToInt()
            }
            if (mixerSplit.isChecked != engine.splitCue) mixerSplit.isChecked = engine.splitCue
            masterLabel.text = "MASTER: ${if (engine.masterDeckIndex == 0) "A" else "B"}"
            styleToggle(cueAButton, engine.decks[0].cueEnabled, getColor(R.color.accent_a))
            styleToggle(cueBButton, engine.decks[1].cueEnabled, getColor(R.color.accent_b))

            for (deck in 0..1) {
                val d = engine.decks[deck]
                midi.setDeckPlay(deck, d.playing)
                midi.setDeckCue(deck, !d.playing && d.hasTrack())
                midi.setDeckSync(deck, d.syncEnabled)
                midi.setDeckCueChannel(deck, d.cueEnabled)
                for (pad in 0 until 8) {
                    midi.setPad(deck, pad, d.padMode == Deck.PAD_HOT_CUE && d.hotCues[pad] != null)
                    midi.setPad(
                        deck,
                        0x60 + pad,
                        d.padMode == Deck.PAD_BEAT_LOOP && loopPads[deck] == pad
                    )
                }
            }
            updateStatusLine()
            ui.postDelayed(this, 120)
        }
    }

    private fun updateStatusLine() {
        val controller = midiName ?: "DDJ-FLX4 not detected"
        val output = engine.routedDeviceName ?: "phone output"
        statusLabel.text = String.format(
            Locale.US,
            "%s  •  %s  •  split cue %s",
            controller,
            output,
            if (engine.splitCue) "on" else "off"
        )
    }

    private fun styleToggle(button: Button, active: Boolean, activeColor: Int) {
        button.backgroundTintList = ColorStateList.valueOf(
            if (active) activeColor else getColor(R.color.panel_alt)
        )
        button.setTextColor(if (active) Color.BLACK else getColor(R.color.text))
    }

    private fun addUnitSlider(bar: SeekBar, setter: (Float) -> Unit) {
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) setter(progress / 1000f)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    // ---------------------------------------------------------------------
    // DeckPanel callbacks (touch UI)
    // ---------------------------------------------------------------------

    override fun onPlay(deck: Int) = engine.decks[deck].togglePlay()
    override fun onCue(deck: Int) = engine.decks[deck].cue()
    override fun onSync(deck: Int) = toggleSync(deck)
    override fun onTap(deck: Int) = tapTempo(deck)

    override fun onTempo(deck: Int, progress: Int) {
        engine.decks[deck].setTempoNormalized((progress - 500) / 500.0)
    }

    override fun onVolume(deck: Int, progress: Int) {
        engine.decks[deck].volume = (progress / 1000f).coerceIn(0f, 1f)
    }

    override fun onTrim(deck: Int, progress: Int) {
        engine.decks[deck].trim = (progress / 1000f).coerceIn(0f, 1f)
    }

    override fun onFilter(deck: Int, progress: Int) {
        engine.decks[deck].filter = (progress / 1000f).coerceIn(0f, 1f)
    }

    override fun onEq(deck: Int, band: Int, progress: Int) {
        val value = (progress / 1000f).coerceIn(0f, 1f)
        when (band) {
            0 -> engine.decks[deck].eqHigh = value
            1 -> engine.decks[deck].eqMid = value
            2 -> engine.decks[deck].eqLow = value
        }
    }

    override fun onLoopIn(deck: Int) {
        engine.decks[deck].setLoopIn()
        clearLoopPad(deck)
    }

    override fun onLoopOut(deck: Int) {
        engine.decks[deck].setLoopOut()
        clearLoopPad(deck)
    }

    override fun onReloop(deck: Int) {
        engine.decks[deck].toggleLoop()
        if (!engine.decks[deck].loopActive) clearLoopPad(deck)
    }

    override fun onPad(deck: Int, pad: Int) {
        val d = engine.decks[deck]
        when (d.padMode) {
            Deck.PAD_HOT_CUE -> d.triggerHotCue(pad)
            Deck.PAD_BEAT_LOOP -> applyBeatLoop(deck, pad)
            Deck.PAD_BEAT_JUMP -> if (pad in JUMP_OFFSETS.indices) {
                d.beatJump(JUMP_OFFSETS[pad])
            }
        }
    }

    override fun onMaster(deck: Int) {
        engine.setMasterDeck(deck)
        toast("Deck ${if (deck == 0) "A" else "B"} is master")
    }

    // ---------------------------------------------------------------------
    // FLX4 callbacks
    // ---------------------------------------------------------------------

    override fun onMidiStatus(name: String?) {
        midiName = name
        updateStatusLine()
    }

    override fun onDeckPlay(deck: Int) = engine.decks[deck].togglePlay()
    override fun onDeckCue(deck: Int) = engine.decks[deck].cue()

    override fun onDeckSync(deck: Int) = toggleSync(deck)

    override fun onDeckSyncLong(deck: Int) {
        engine.setMasterDeck(deck)
        toast("Deck ${if (deck == 0) "A" else "B"} is master")
    }

    override fun onDeckTempo(deck: Int, value14: Int) {
        val normalized = ((value14 - 8192) / 8192.0).coerceIn(-1.0, 1.0)
        engine.decks[deck].setTempoNormalized(normalized)
    }

    override fun onDeckVolume(deck: Int, value14: Int) {
        engine.decks[deck].volume = unit(value14)
    }

    override fun onDeckTrim(deck: Int, value14: Int) {
        engine.decks[deck].trim = unit(value14)
    }

    override fun onDeckEq(deck: Int, band: Int, value14: Int) {
        val value = unit(value14)
        when (band) {
            0 -> engine.decks[deck].eqHigh = value
            1 -> engine.decks[deck].eqMid = value
            2 -> engine.decks[deck].eqLow = value
        }
    }

    override fun onDeckFilter(deck: Int, value14: Int) {
        engine.decks[deck].filter = unit(value14)
    }

    override fun onDeckCueToggle(deck: Int) {
        engine.decks[deck].cueEnabled = !engine.decks[deck].cueEnabled
    }

    override fun onDeckTempoRange(deck: Int) {
        engine.decks[deck].cycleTempoRange()
        toast("Tempo range ${(engine.decks[deck].tempoRange * 100).roundToInt()}%")
    }

    override fun onDeckQuantize(deck: Int) {
        engine.decks[deck].quantize = !engine.decks[deck].quantize
        toast("Quantize ${if (engine.decks[deck].quantize) "on" else "off"}")
    }

    override fun onDeckJog(deck: Int, delta: Int, shift: Boolean) {
        val d = engine.decks[deck]
        val secondsPerTick = when {
            shift -> 0.02
            d.playing -> 0.003
            else -> 0.012
        }
        d.seekTo((d.positionSec + delta * secondsPerTick).coerceAtLeast(0.0))
    }

    override fun onCrossfader(value14: Int) {
        engine.crossfader = unit(value14)
    }

    override fun onHeadphoneMix(value14: Int) {
        engine.headphoneMix = unit(value14)
    }

    override fun onBrowse(delta: Int) {
        if (tracks.isEmpty()) {
            showLibrary(true)
            return
        }
        showLibrary(true)
        val next = (selectedTrack + if (delta > 0) 1 else -1)
            .coerceIn(0, tracks.size - 1)
        if (next != selectedTrack) {
            selectedTrack = next
            libraryAdapter.notifyDataSetChanged()
            libraryList.smoothScrollToPosition(next)
        }
    }

    override fun onBrowsePress() {
        showLibrary(libraryPanel.visibility != View.VISIBLE)
    }

    override fun onLoad(deck: Int) {
        if (tracks.isEmpty()) {
            showLibrary(true)
            toast("Choose a music folder first")
        } else {
            loadSelected(deck)
        }
    }

    override fun onPad(deck: Int, note: Int, shift: Boolean) {
        val d = engine.decks[deck]
        when (d.padMode) {
            Deck.PAD_HOT_CUE -> {
                if (note in 0..7) {
                    if (shift) d.deleteHotCue(note) else d.triggerHotCue(note)
                }
            }

            Deck.PAD_BEAT_LOOP -> {
                if (shift) {
                    d.toggleLoop()
                    if (!d.loopActive) clearLoopPad(deck)
                } else if (note in 0x60..0x67) {
                    applyBeatLoop(deck, note - 0x60)
                }
            }

            Deck.PAD_BEAT_JUMP -> {
                if (!shift && note in 0x20..0x27) {
                    d.beatJump(JUMP_OFFSETS[note - 0x20])
                }
            }
        }
    }

    override fun onPadMode(deck: Int, mode: Int) {
        engine.decks[deck].setPadMode(mode)
        clearLoopPad(deck)
    }

    // ---------------------------------------------------------------------
    // Sync and tempo helpers
    // ---------------------------------------------------------------------

    private fun toggleSync(index: Int) {
        val deck = engine.decks[index]
        if (deck.syncEnabled) {
            deck.releaseSync()
            return
        }
        if (!deck.grid.valid) {
            toast("Load and analyze a track before syncing")
            return
        }
        val otherIndex = 1 - index
        val other = engine.decks[otherIndex]
        if (!other.grid.valid) {
            toast("The other deck has no BPM grid yet")
            return
        }
        engine.setMasterDeck(otherIndex)
        deck.engageSync(other)
        toast("SYNC: ${other.effectiveBpm().roundToInt()} BPM")
    }

    private fun tapTempo(index: Int) {
        val now = SystemClock.elapsedRealtime()
        val tapsForDeck = taps[index]
        if (tapsForDeck.isNotEmpty() && now - tapsForDeck.last() > 2200) tapsForDeck.clear()
        tapsForDeck.add(now)
        if (tapsForDeck.size > 8) tapsForDeck.removeAt(0)
        if (tapsForDeck.size >= 3) {
            var total = 0L
            for (i in 1 until tapsForDeck.size) total += tapsForDeck[i] - tapsForDeck[i - 1]
            val average = total / (tapsForDeck.size - 1).toDouble()
            if (average > 0.0) {
                var bpm = 60_000.0 / average
                while (bpm < 70.0) bpm *= 2.0
                while (bpm > 180.0) bpm /= 2.0
                engine.decks[index].setGrid(bpm, engine.decks[index].positionSec, 1.0)
            }
        }
    }

    private fun applyBeatLoop(deck: Int, index: Int) {
        val d = engine.decks[deck]
        if (!d.grid.valid) {
            toast("Tap the tempo first")
            return
        }
        val beats = 0.25 * 2.0.pow(index)
        d.setBeatLoop(beats)
        loopPads[deck] = if (d.loopActive) index else -1
        deckPanels[deck].setLoopPad(loopPads[deck])
    }

    private fun clearLoopPad(deck: Int) {
        loopPads[deck] = -1
        deckPanels[deck].setLoopPad(-1)
    }

    private fun unit(value14: Int): Float = (value14 / 16383f).coerceIn(0f, 1f)

    // ---------------------------------------------------------------------
    // Library
    // ---------------------------------------------------------------------

    private fun showLibrary(show: Boolean) {
        libraryPanel.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun chooseFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        try {
            startActivityForResult(intent, REQ_FOLDER)
        } catch (e: Exception) {
            toast("No file picker available: ${e.message}")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_FOLDER || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
            // The folder can still be used for this session.
        }
        folderUri = uri
        preferences.edit().putString(PREF_FOLDER, uri.toString()).apply()
        scanFolder(uri)
    }

    private fun scanFolder(uri: Uri) {
        libraryPathLabel.text = "Scanning…"
        work.execute {
            val found = try {
                library.scan(uri)
            } catch (e: Exception) {
                emptyList()
            }
            ui.post {
                tracks.clear()
                tracks.addAll(found)
                selectedTrack = if (found.isEmpty()) -1 else 0
                libraryAdapter.notifyDataSetChanged()
                libraryPathLabel.text = if (found.isEmpty()) {
                    "No audio files in this folder"
                } else {
                    "${found.size} tracks"
                }
                if (found.isEmpty()) toast("No audio files found")
            }
        }
    }

    private fun loadSelected(deck: Int) {
        val track = tracks.getOrNull(selectedTrack)
        if (track == null) {
            showLibrary(true)
            toast("Select a track first")
            return
        }
        loadTrack(deck, track)
    }

    private fun loadTrack(index: Int, track: Track) {
        val token = loadTokens.incrementAndGet(index)
        engine.decks[index].clear()
        clearLoopPad(index)
        deckPanels[index].setLoading("Loading ${track.name}…")
        work.execute {
            var source: AudioSource? = null
            try {
                source = AudioSource(this, track.uri)
                var title = track.name.substringBeforeLast('.').ifBlank { track.name }
                var artist = ""
                var duration = source.durationSec
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(this, track.uri)
                    title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                        ?.takeIf { it.isNotBlank() } ?: title
                    artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                        ?: ""
                    if (duration <= 0.0) {
                        duration = (retriever.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_DURATION
                        )?.toDoubleOrNull() ?: 0.0) / 1000.0
                    }
                } catch (_: Exception) {
                } finally {
                    retriever.release()
                }

                val loaded = source
                val loadedTitle = title
                val loadedArtist = artist
                val loadedDuration = duration
                ui.post {
                    if (loadTokens.get(index) != token) {
                        loaded.close()
                        return@post
                    }
                    engine.decks[index].load(
                        loaded,
                        loadedTitle,
                        loadedArtist,
                        loadedDuration
                    )
                    loaded.start()
                    deckPanels[index].setLoading(null)
                }

                val analysis = cache.get(track) ?: try {
                    BpmAnalyzer.analyze(this, track.uri)
                } catch (_: Exception) {
                    null
                }
                if (analysis != null) {
                    cache.put(track, analysis)
                    ui.post {
                        if (loadTokens.get(index) != token) return@post
                        engine.decks[index].setGrid(
                            analysis.bpm,
                            analysis.anchorSec,
                            analysis.confidence
                        )
                    }
                }
            } catch (e: Exception) {
                try {
                    source?.close()
                } catch (_: Exception) {
                }
                ui.post {
                    if (loadTokens.get(index) != token) return@post
                    deckPanels[index].setError("Unsupported or unreadable file")
                    toast(e.message ?: "Cannot read this file")
                }
            }
        }
    }

    private inner class TrackAdapter : BaseAdapter() {
        override fun getCount(): Int = tracks.size
        override fun getItem(position: Int): Any = tracks[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(
                android.R.layout.simple_list_item_2,
                parent,
                false
            )
            val track = tracks[position]
            val title = view.findViewById<TextView>(android.R.id.text1)
            val subtitle = view.findViewById<TextView>(android.R.id.text2)
            title.text = track.name.substringBeforeLast('.').ifBlank { track.name }
            title.setTextColor(getColor(R.color.text))
            title.textSize = 13f
            subtitle.setTextColor(getColor(R.color.text_dim))
            subtitle.textSize = 9f
            val cached = cache.get(track)
            subtitle.text = if (cached != null) {
                String.format(
                    Locale.US,
                    "%.1f BPM  •  %s",
                    cached.bpm,
                    formatTime(cached.durationSec)
                )
            } else {
                "Double-tap LOAD on the FLX4 to load"
            }
            view.setBackgroundColor(
                if (position == selectedTrack) getColor(R.color.line) else Color.TRANSPARENT
            )
            return view
        }
    }

    private fun formatTime(seconds: Double): String {
        if (!seconds.isFinite() || seconds <= 0.0) return "--:--"
        val total = seconds.toInt()
        return String.format(Locale.US, "%02d:%02d", total / 60, total % 60)
    }

    // ---------------------------------------------------------------------
    // Audio output selection
    // ---------------------------------------------------------------------

    private fun showAudioOutputs() {
        val manager = getSystemService(AudioManager::class.java)
        val devices = try {
            manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.isSink }
        } catch (_: Exception) {
            emptyList()
        }
        val labels = ArrayList<String>()
        devices.forEach { labels.add("${it.productName}  (${deviceTypeName(it.type)})") }
        labels.add("Phone default")
        AlertDialog.Builder(this)
            .setTitle("Audio output")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == devices.size) {
                    engine.setOutputDevice(null)
                    toast("Using the phone default output")
                } else {
                    val accepted = engine.setOutputDevice(devices[which])
                    toast(
                        if (accepted) "Output: ${devices[which].productName}"
                        else "This phone refused that output; check the USB audio support"
                    )
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deviceTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "headphones"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "headset"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB accessory"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth LE"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE headset"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        else -> "output $type"
    }

    // ---------------------------------------------------------------------
    // In-app updates
    // ---------------------------------------------------------------------

    private fun installedVersionCode(): Long = try {
        val info = packageManager.getPackageInfo(packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    } catch (_: Exception) {
        0L
    }

    private fun checkForUpdates(manual: Boolean) {
        if (updateCheckRunning) return
        val now = System.currentTimeMillis()
        if (!manual && now - lastUpdateCheckMillis < UPDATE_INTERVAL_MS) return
        lastUpdateCheckMillis = now
        preferences.edit().putLong(PREF_LAST_UPDATE_CHECK, now).apply()
        updateCheckRunning = true
        updateButton.text = "CHECKING…"
        val current = installedVersionCode()
        work.execute {
            val info = try {
                UpdateChecker.check(
                    BuildConfig.UPDATE_OWNER,
                    BuildConfig.UPDATE_REPO,
                    current
                )
            } catch (_: Exception) {
                null
            }
            ui.post {
                updateCheckRunning = false
                updateInfo = info
                resetUpdateButton()
                if (info != null) {
                    updateButton.text = "UPDATE ${info.versionName}"
                    updateButton.backgroundTintList =
                        ColorStateList.valueOf(getColor(R.color.accent))
                    updateButton.setTextColor(Color.BLACK)
                    if (manual) showUpdateDialog(info) else {
                        toast("Update ${info.versionName} available")
                    }
                } else if (manual) {
                    toast("No update found (or GitHub unreachable)")
                }
            }
        }
    }

    private fun resetUpdateButton() {
        updateButton.backgroundTintList = ColorStateList.valueOf(getColor(R.color.panel_alt))
        updateButton.setTextColor(getColor(R.color.text))
        updateButton.text = "CHECK UPDATES"
    }

    private fun showUpdateDialog(info: UpdateChecker.UpdateInfo) {
        val notes = info.notes.take(1400).ifBlank { "Bug fixes and improvements." }
        AlertDialog.Builder(this)
            .setTitle("Update ${info.versionName}")
            .setMessage("A new FLX4 Pocket version is available.\n\n$notes")
            .setPositiveButton("Download & install") { _, _ -> beginUpdate(info) }
            .setNeutralButton("GitHub") { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.htmlUrl)))
                } catch (_: Exception) {
                }
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun beginUpdate(info: UpdateChecker.UpdateInfo) {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            pendingUpdateInfo = info
            AlertDialog.Builder(this)
                .setTitle("Allow app installs")
                .setMessage(
                    "Android must allow FLX4 Pocket to install its own updates. " +
                        "Enable \"Allow from this source\", then tap the update button again."
                )
                .setPositiveButton("Open settings") { _, _ ->
                    try {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:$packageName")
                            )
                        )
                    } catch (e: Exception) {
                        toast("Could not open settings: ${e.message}")
                    }
                }
                .setNegativeButton("Cancel") { _, _ -> pendingUpdateInfo = null }
                .show()
            return
        }

        downloadCancelled = false
        val progress = AlertDialog.Builder(this)
            .setTitle("Downloading ${info.versionName}")
            .setMessage("0%")
            .setNegativeButton("Cancel") { _, _ -> downloadCancelled = true }
            .setCancelable(false)
            .create()
        activeUpdateDialog = progress
        progress.show()

        work.execute {
            try {
                val apk = UpdateChecker.download(
                    info = info,
                    cacheDir = cacheDir,
                    onProgress = { percent ->
                        ui.post {
                            if (progress.isShowing) progress.setMessage("$percent%")
                        }
                    },
                    cancelled = { downloadCancelled }
                )
                ui.post {
                    progress.dismiss()
                    activeUpdateDialog = null
                    try {
                        UpdateInstaller.install(this, apk)
                        toast("Confirm the installation in the Android dialog")
                    } catch (e: Exception) {
                        AlertDialog.Builder(this)
                            .setTitle("Cannot install the update")
                            .setMessage(e.message ?: "Unknown error")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            } catch (e: Exception) {
                ui.post {
                    progress.dismiss()
                    activeUpdateDialog = null
                    if (!downloadCancelled) {
                        AlertDialog.Builder(this)
                            .setTitle("Update download failed")
                            .setMessage(e.message ?: "Unknown error")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                    downloadCancelled = false
                }
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REQ_FOLDER = 41
        private const val PREFS = "flx4-preferences"
        private const val PREF_FOLDER = "folder"
        private const val PREF_SPLIT = "split_cue"
        private const val PREF_LAST_UPDATE_CHECK = "last_update_check"
        private const val UPDATE_INTERVAL_MS = 6 * 60 * 60 * 1000L
        private val JUMP_OFFSETS = doubleArrayOf(-1.0, 1.0, -2.0, 2.0, -4.0, 4.0, -8.0, 8.0)
    }
}
