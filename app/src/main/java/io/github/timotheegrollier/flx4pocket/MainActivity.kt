package io.github.timotheegrollier.flx4pocket

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicIntegerArray
import kotlin.math.roundToInt

/**
 * The single screen of the app and its wiring layer: touch controls and FLX4
 * MIDI events both end up as calls on [AudioEngine] and its [Deck]s, and a
 * per-frame refresh mirrors their state back to the screen and the LEDs.
 */
class MainActivity : Activity(), Flx4Listener, DeckPanel.Host, Choreographer.FrameCallback {

    internal lateinit var engine: AudioEngine // internal: the UI test loads decks directly
    private lateinit var midi: Flx4Midi
    private lateinit var library: Library
    private lateinit var cache: AnalysisCache
    private lateinit var work: ExecutorService
    private lateinit var updates: UpdateUi
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var panels: List<DeckPanel>
    private lateinit var waves: List<WaveformView>
    private lateinit var statusLabel: TextView
    private lateinit var masterVu: VuView
    private lateinit var crossfader: FaderView
    private lateinit var smartCfx: TextView
    private lateinit var masterCue: TextView
    private lateinit var fxSelect: TextView
    private lateinit var fxBeats: TextView
    private lateinit var fxTarget: TextView
    private lateinit var fxDepth: FaderView
    private lateinit var fxOn: TextView
    private lateinit var libraryPanel: View
    private lateinit var libraryList: ListView
    private lateinit var libraryInfo: TextView
    private lateinit var librarySearch: EditText

    private val tracks = ArrayList<Track>()
    private var visibleTracks: List<Track> = emptyList()
    private var selectedTrack = -1
    private val trackAdapter = TrackAdapter()

    private val loadTokens = AtomicIntegerArray(2)
    private val loadedTracks = arrayOfNulls<Track>(2)
    private val taps = Array(2) { ArrayList<Long>() }
    private val loopInDownAt = LongArray(2)
    private val fxChannels = booleanArrayOf(true, true)
    private var fxBeatIndex = 2 // 1/2 beat
    private var midiName: String? = null
    private var frame = 0
    private var lastBackPress = 0L

    private val preferences by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            engine.decks.forEach { it.pause() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeControlStream = AudioManager.STREAM_MUSIC

        engine = AudioEngine(this)
        engine.splitCue = preferences.getBoolean(PREF_SPLIT, false)
        library = Library(this)
        cache = AnalysisCache(this)
        work = Executors.newFixedThreadPool(4)

        setContentView(R.layout.activity_main)
        statusLabel = findViewById(R.id.status)
        masterVu = findViewById(R.id.master_vu)
        setupDecks()
        setupMixer()
        setupLibrary()
        findViewById<View>(R.id.btn_library).setOnClickListener { showLibrary(!libraryVisible) }
        findViewById<View>(R.id.btn_audio).setOnClickListener { showAudioSettings() }
        updates = UpdateUi(this, findViewById(R.id.btn_update), work) { engine.decks.any { it.playing } }
        updates.start()

        engine.start()
        midi = Flx4Midi(this, this)
        midi.start()
        getSystemService(AudioManager::class.java).requestAudioFocus(
            focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN
        )

        val savedFolder = preferences.getString(PREF_FOLDER, null)
        if (savedFolder != null) {
            scanFolder(Uri.parse(savedFolder))
        } else {
            libraryInfo.text = "Choose your music folder"
            showLibrary(true)
        }
    }

    override fun onResume() {
        super.onResume()
        updates.onResume()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onPause() {
        Choreographer.getInstance().removeFrameCallback(this)
        super.onPause()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        updates.stop()
        midi.close()
        engine.close()
        work.shutdownNow()
        getSystemService(AudioManager::class.java).abandonAudioFocus(focusListener)
        super.onDestroy()
    }

    /** Full-screen: every dp of a phone in landscape goes to the decks. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        }
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onBackPressed() {
        val now = SystemClock.uptimeMillis()
        when {
            libraryVisible -> showLibrary(false)
            // A stray back gesture must not end a set.
            engine.decks.any { it.playing } && now - lastBackPress > 2500 -> {
                lastBackPress = now
                toast(getString(R.string.back_again))
            }
            else -> super.onBackPressed()
        }
    }

    // ---------------------------------------------------------------------
    // Setup
    // ---------------------------------------------------------------------

    private fun setupDecks() {
        val headers = listOf<View>(findViewById(R.id.wave_a), findViewById(R.id.wave_b))
        val strips = listOf<View>(findViewById(R.id.channel_a), findViewById(R.id.channel_b))
        val accents = listOf(getColor(R.color.accent_a), getColor(R.color.accent_b))
        panels = listOf(findViewById(R.id.deck_a), findViewById(R.id.deck_b))
        waves = headers.map { it.findViewById(R.id.wave) }
        val zoom = preferences.getFloat(PREF_ZOOM, 8f).toDouble()
        panels.forEachIndexed { i, panel ->
            panel.bind(engine.decks[i], accents[i], headers[i], strips[i], this) { seconds ->
                // Both waveforms share one zoom so their beats stay comparable.
                waves.forEach { it.visibleSeconds = seconds }
                preferences.edit().putFloat(PREF_ZOOM, seconds.toFloat()).apply()
            }
            waves[i].visibleSeconds = zoom
        }
    }

    private fun setupMixer() {
        val fxColor = getColor(R.color.accent)
        crossfader = findViewById(R.id.mixer_crossfader)
        crossfader.centered = true
        crossfader.accent = getColor(R.color.text)
        crossfader.onChange = { engine.crossfader = it }

        smartCfx = findViewById(R.id.mixer_smart_cfx)
        smartCfx.setOnClickListener { toggleSmartCfx() }
        masterCue = findViewById(R.id.mixer_master_cue)
        masterCue.setOnClickListener { engine.masterCue = !engine.masterCue }

        fxSelect = findViewById(R.id.fx_select)
        fxSelect.setOnClickListener { stepFxType(1) }
        fxBeats = findViewById(R.id.fx_beats)
        findViewById<View>(R.id.fx_beat_down).setOnClickListener { stepFxBeats(-1) }
        findViewById<View>(R.id.fx_beat_up).setOnClickListener { stepFxBeats(1) }
        fxTarget = findViewById(R.id.fx_target)
        fxTarget.setOnClickListener { engine.fxTarget = (engine.fxTarget + 1) % 3 }
        fxDepth = findViewById(R.id.fx_depth)
        fxDepth.accent = fxColor
        fxDepth.onChange = { engine.beatFx.depth = it }
        fxOn = findViewById(R.id.fx_on)
        fxOn.setOnClickListener { engine.beatFx.enabled = !engine.beatFx.enabled }
        engine.beatFx.beats = FX_BEATS[fxBeatIndex]
    }

    private fun setupLibrary() {
        libraryPanel = findViewById(R.id.library_panel)
        libraryList = findViewById(R.id.library_list)
        libraryInfo = findViewById(R.id.library_info)
        librarySearch = findViewById(R.id.library_search)
        libraryList.adapter = trackAdapter
        libraryList.setOnItemClickListener { _, _, position, _ ->
            selectedTrack = position
            trackAdapter.notifyDataSetChanged()
        }
        librarySearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = filterTracks()
        })
        findViewById<View>(R.id.library_folder).setOnClickListener { chooseFolder() }
        findViewById<View>(R.id.library_close).setOnClickListener { showLibrary(false) }
    }

    // ---------------------------------------------------------------------
    // Refresh: screen and controller LEDs
    // ---------------------------------------------------------------------

    override fun doFrame(frameTimeNanos: Long) {
        Choreographer.getInstance().postFrameCallback(this)
        // Control positions and meters follow the engine every frame; texts,
        // button states and LEDs are refreshed a few times per second.
        panels.forEachIndexed { i, panel -> panel.refreshControls(engine.level(i)) }
        crossfader.show(engine.crossfader)
        fxDepth.show(engine.beatFx.depth)
        masterVu.setLevel(maxOf(engine.meterL, engine.meterR))
        if (frame++ % 6 != 0) return

        panels.forEachIndexed { i, panel -> panel.refresh(engine.masterDeckIndex == i) }
        val fx = engine.beatFx
        val fxColor = getColor(R.color.accent)
        val dark = getColor(R.color.on_accent)
        val idle = getColor(R.color.panel_alt)
        val text = getColor(R.color.text)
        fxSelect.show(fx.type.label)
        fxBeats.show(FX_BEAT_LABELS[fxBeatIndex])
        fxTarget.show(FX_TARGET_LABELS[engine.fxTarget])
        if (fx.enabled) fxOn.tint(fxColor, dark) else fxOn.tint(idle, text)
        if (engine.decks[0].smartCfx) smartCfx.tint(fxColor, dark) else smartCfx.tint(idle, text)
        if (engine.masterCue) masterCue.tint(fxColor, dark) else masterCue.tint(idle, text)
        updateStatus()
        if (midi.connected) updateLeds()
    }

    private fun updateStatus() {
        val problem = engine.error
        if (problem != null) {
            statusLabel.show(problem)
            statusLabel.setTextColor(getColor(R.color.danger))
            return
        }
        val output = engine.outputName ?: getString(R.string.status_phone_output)
        val layout = when {
            engine.outputChannels >= 4 -> "master 1-2 + phones 3-4"
            engine.splitCue -> "split cue"
            else -> "stereo"
        }
        statusLabel.show("${midiName ?: getString(R.string.status_no_controller)}  ·  $output ($layout)")
        statusLabel.setTextColor(getColor(if (midiName != null) R.color.ok else R.color.text_dim))
    }

    private fun updateLeds() {
        for (index in 0..1) {
            val d = engine.decks[index]
            val mode = d.padMode
            midi.setLed(index, MidiMap.NOTE_PLAY, d.playing)
            midi.setLed(index, MidiMap.NOTE_CUE, d.hasTrack() && !d.playing)
            midi.setLed(index, MidiMap.NOTE_SYNC, d.syncEnabled)
            midi.setLed(index, MidiMap.NOTE_CUE_CHANNEL, d.cueEnabled)
            midi.setLed(index, MidiMap.NOTE_LOOP_IN, d.loopInSec >= 0.0)
            midi.setLed(index, MidiMap.NOTE_LOOP_OUT, d.loopActive)
            midi.setLed(index, MidiMap.NOTE_HOT_CUE_MODE, mode == Deck.PAD_HOT_CUE)
            midi.setLed(index, MidiMap.NOTE_PAD_FX1_MODE, mode == Deck.PAD_FX1)
            midi.setLed(index, MidiMap.NOTE_BEAT_JUMP_MODE, mode == Deck.PAD_BEAT_JUMP)
            midi.setLed(index, MidiMap.NOTE_PAD_FX2_MODE, mode == Deck.PAD_FX2)
            midi.setLed(index, MidiMap.NOTE_BEAT_LOOP_MODE, mode == Deck.PAD_BEAT_LOOP)
            val loopBeats = d.loopBeats()
            for (pad in 0..7) {
                midi.setPad(index, MidiMap.PAD_BASE_HOT_CUE + pad, mode == Deck.PAD_HOT_CUE && d.hotCues[pad] != null)
                midi.setPad(index, MidiMap.PAD_BASE_BEAT_LOOP + pad, mode == Deck.PAD_BEAT_LOOP && loopBeats == Deck.LOOP_BEATS[pad])
                midi.setPad(index, MidiMap.PAD_BASE_BEAT_JUMP + pad, mode == Deck.PAD_BEAT_JUMP)
                midi.setPad(index, MidiMap.PAD_BASE_FX1 + pad, mode == Deck.PAD_FX1 && d.heldFxPad == pad)
                midi.setPad(index, MidiMap.PAD_BASE_FX2 + pad, mode == Deck.PAD_FX2 && d.heldFxPad == pad)
            }
            midi.setLevel(index, engine.level(index))
        }
        // The FX ON/OFF LED blinks on 0x7F and stays lit on 0x00.
        midi.setLed(MidiMap.CH_FX, MidiMap.NOTE_FX_ON, engine.beatFx.enabled)
        midi.setLed(MidiMap.CH_MIXER, MidiMap.NOTE_SMART_CFX, engine.decks[0].smartCfx)
        midi.setLed(MidiMap.CH_MIXER, MidiMap.NOTE_MASTER_CUE, engine.masterCue)
    }

    // ---------------------------------------------------------------------
    // Actions shared by the touch UI and the controller
    // ---------------------------------------------------------------------

    override fun onPlay(deck: Int) {
        if (engine.decks[deck].hasTrack()) engine.decks[deck].togglePlay()
        else toast(getString(R.string.play_no_track))
    }

    override fun onSync(deck: Int) {
        val d = engine.decks[deck]
        val other = engine.decks[1 - deck]
        when {
            d.syncEnabled -> d.releaseSync()
            !d.grid.valid -> toast("This deck has no BPM yet — wait for the analysis or TAP the tempo")
            !other.grid.valid -> toast("The other deck has no BPM yet")
            else -> {
                engine.setMasterDeck(1 - deck)
                d.engageSync(other)
            }
        }
    }

    override fun onMaster(deck: Int) {
        engine.setMasterDeck(deck)
        toast("Deck ${DECK_NAMES[deck]} is the sync master")
    }

    override fun onTap(deck: Int) {
        val now = SystemClock.elapsedRealtime()
        val times = taps[deck]
        if (times.isNotEmpty() && now - times.last() > 2200) times.clear()
        times.add(now)
        if (times.size > 8) times.removeAt(0)
        if (times.size < 3) return
        var bpm = 60_000.0 * (times.size - 1) / (times.last() - times.first())
        while (bpm < 70.0) bpm *= 2.0
        while (bpm > 180.0) bpm /= 2.0
        val d = engine.decks[deck]
        d.setGrid(bpm, d.positionSec, 2.0)
    }

    override fun onHotCuesChanged(deck: Int) {
        loadedTracks[deck]?.let { cache.putHotCues(it, engine.decks[deck].hotCues) }
    }

    private fun toggleSmartCfx() {
        val on = !engine.decks[0].smartCfx
        engine.decks.forEach { it.smartCfx = on }
    }

    private fun stepFxType(step: Int) {
        val types = FxType.values()
        engine.beatFx.type = types[Math.floorMod(engine.beatFx.type.ordinal + step, types.size)]
    }

    private fun stepFxBeats(step: Int) {
        fxBeatIndex = (fxBeatIndex + step).coerceIn(0, FX_BEATS.size - 1)
        engine.beatFx.beats = FX_BEATS[fxBeatIndex]
    }

    // ---------------------------------------------------------------------
    // DDJ-FLX4
    // ---------------------------------------------------------------------

    override fun onMidiStatus(name: String?) {
        midiName = name
        if (name != null) toast("$name connected")
    }

    override fun onButton(deck: Int, button: Flx4Button, down: Boolean) {
        val d = engine.decks.getOrNull(deck)
        when (button) {
            Flx4Button.CUE -> if (down) d?.cueDown() else d?.cueUp()
            Flx4Button.JOG_TOUCH -> if (down) d?.scratchBegin() else d?.scratchEnd()
            Flx4Button.FX_CHANNEL -> {
                // A three-position switch: CH1, CH2, or both for the master.
                fxChannels[deck] = down
                engine.fxTarget = when {
                    fxChannels[0] && !fxChannels[1] -> AudioEngine.FX_DECK_A
                    fxChannels[1] && !fxChannels[0] -> AudioEngine.FX_DECK_B
                    else -> AudioEngine.FX_MASTER
                }
            }
            Flx4Button.LOOP_IN -> if (d != null) {
                val now = SystemClock.uptimeMillis()
                if (down) {
                    d.setLoopIn()
                    loopInDownAt[deck] = now
                } else if (now - loopInDownAt[deck] > 500) {
                    d.loopFromIn(4.0) // IN / 4 BEAT held
                }
            }
            else -> if (down) onPress(deck, d, button)
        }
    }

    private fun onPress(deck: Int, d: Deck?, button: Flx4Button) {
        when (button) {
            Flx4Button.PLAY -> onPlay(deck)
            Flx4Button.TO_START -> d?.seekTo(0.0)
            Flx4Button.SYNC -> onSync(deck)
            Flx4Button.MASTER -> onMaster(deck)
            Flx4Button.TEMPO_RANGE -> d?.cycleTempoRange()
            Flx4Button.LOOP_OUT -> d?.setLoopOut()
            Flx4Button.RELOOP -> d?.toggleLoop()
            Flx4Button.RELOOP_STOP -> {
                d?.exitLoop()
                d?.pause()
            }
            Flx4Button.LOOP_HALVE -> d?.scaleLoop(0.5)
            Flx4Button.LOOP_DOUBLE -> d?.scaleLoop(2.0)
            Flx4Button.JUMP_BACK -> d?.beatJump(-QUICK_JUMP_BEATS)
            Flx4Button.JUMP_FORWARD -> d?.beatJump(QUICK_JUMP_BEATS)
            Flx4Button.PFL -> d?.let { it.cueEnabled = !it.cueEnabled }
            Flx4Button.QUANTIZE -> d?.let {
                it.quantize = !it.quantize
                toast("Quantize ${if (it.quantize) "on" else "off"}")
            }
            Flx4Button.LOAD -> loadSelected(deck)
            Flx4Button.BROWSE_PRESS -> showLibrary(!libraryVisible)
            Flx4Button.SMART_CFX -> toggleSmartCfx()
            Flx4Button.SMART_FADER -> toast(getString(R.string.not_supported, "Smart Fader"))
            Flx4Button.MASTER_CUE -> engine.masterCue = !engine.masterCue
            Flx4Button.FX_SELECT -> stepFxType(1)
            Flx4Button.FX_SELECT_PREV -> stepFxType(-1)
            Flx4Button.FX_BEAT_LEFT -> stepFxBeats(-1)
            Flx4Button.FX_BEAT_RIGHT -> stepFxBeats(1)
            Flx4Button.FX_ON -> engine.beatFx.enabled = !engine.beatFx.enabled
            Flx4Button.FX_CLEAR -> engine.beatFx.enabled = false
            else -> Unit // SHIFT itself: its combinations arrive as distinct notes
        }
    }

    override fun onKnob(deck: Int, knob: Flx4Knob, value: Float) {
        val d = engine.decks.getOrNull(deck)
        when (knob) {
            // The fader's low end is its "+" side: 0 is fastest, 1 is slowest.
            Flx4Knob.TEMPO -> d?.setTempoNormalized(1.0 - 2.0 * value)
            Flx4Knob.TRIM -> d?.trim = value
            Flx4Knob.EQ_HIGH -> d?.eqHigh = value
            Flx4Knob.EQ_MID -> d?.eqMid = value
            Flx4Knob.EQ_LOW -> d?.eqLow = value
            Flx4Knob.VOLUME -> d?.volume = value
            Flx4Knob.FILTER -> d?.filter = value
            Flx4Knob.CROSSFADER -> engine.crossfader = value
            Flx4Knob.HEADPHONE_MIX -> engine.headphoneMix = value
            Flx4Knob.HEADPHONE_LEVEL -> engine.headphoneGain = value
            Flx4Knob.MASTER_LEVEL -> engine.masterGain = value
            Flx4Knob.FX_DEPTH -> engine.beatFx.depth = value
        }
    }

    override fun onJog(deck: Int, jog: Flx4Jog, delta: Int) {
        val d = engine.decks[deck]
        when (jog) {
            Flx4Jog.SCRATCH -> if (d.scratching) d.scratchMove(delta * Deck.JOG_TICK_SECONDS) else d.bend(delta)
            Flx4Jog.BEND -> d.bend(delta)
            Flx4Jog.SEARCH -> d.seekBy(delta * 0.2)
        }
    }

    override fun onPadMode(deck: Int, mode: Int) {
        when (mode) {
            Deck.PAD_SAMPLER -> toast(getString(R.string.not_supported, "Sampler"))
            Deck.PAD_KEYBOARD -> toast(getString(R.string.not_supported, "Keyboard mode"))
            Deck.PAD_KEY_SHIFT -> toast(getString(R.string.not_supported, "Key Shift"))
            else -> engine.decks[deck].padMode = mode
        }
    }

    override fun onPad(deck: Int, mode: Int, pad: Int, shift: Boolean, down: Boolean) {
        val d = engine.decks[deck]
        if (!down) {
            d.padUp(mode, pad)
            return
        }
        d.padDown(mode, pad, shift)
        if (mode == Deck.PAD_HOT_CUE) onHotCuesChanged(deck)
    }

    override fun onBrowse(delta: Int, shift: Boolean) {
        showLibrary(true)
        if (visibleTracks.isEmpty()) return
        selectedTrack = (selectedTrack + delta * if (shift) 10 else 1).coerceIn(0, visibleTracks.size - 1)
        trackAdapter.notifyDataSetChanged()
        libraryList.smoothScrollToPosition(selectedTrack)
    }

    // ---------------------------------------------------------------------
    // Library
    // ---------------------------------------------------------------------

    private val libraryVisible: Boolean get() = libraryPanel.visibility == View.VISIBLE

    private fun showLibrary(show: Boolean) {
        libraryPanel.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) {
            getSystemService(InputMethodManager::class.java)
                .hideSoftInputFromWindow(librarySearch.windowToken, 0)
            librarySearch.clearFocus()
        }
    }

    private fun chooseFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        try {
            @Suppress("DEPRECATION")
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
        // Without the persisted grant the folder still works for this session.
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        preferences.edit().putString(PREF_FOLDER, uri.toString()).apply()
        scanFolder(uri)
    }

    private fun scanFolder(uri: Uri) {
        libraryInfo.text = "Scanning…"
        work.execute {
            val found = try {
                library.scan(uri)
            } catch (_: Exception) {
                emptyList()
            }
            ui.post {
                tracks.clear()
                tracks.addAll(found)
                filterTracks()
            }
        }
    }

    private fun filterTracks() {
        val query = librarySearch.text.toString().trim()
        visibleTracks = if (query.isEmpty()) tracks else tracks.filter { it.name.contains(query, ignoreCase = true) }
        selectedTrack = if (visibleTracks.isEmpty()) -1 else 0
        trackAdapter.notifyDataSetChanged()
        libraryInfo.text = when {
            tracks.isEmpty() -> "No audio files — choose a folder"
            query.isEmpty() -> "${tracks.size} tracks"
            else -> "${visibleTracks.size} of ${tracks.size}"
        }
    }

    private fun loadSelected(deck: Int) {
        val track = visibleTracks.getOrNull(selectedTrack)
        if (track == null) {
            showLibrary(true)
            toast("Select a track first")
        } else {
            loadTrack(deck, track)
        }
    }

    private fun loadTrack(index: Int, track: Track) {
        val token = loadTokens.incrementAndGet(index)
        val deck = engine.decks[index]
        val panel = panels[index]
        val stale = { loadTokens.get(index) != token }
        deck.clear()
        loadedTracks[index] = null
        panel.message = "Loading ${track.name}…"
        panel.messageIsError = false
        work.execute {
            try {
                val source = AudioSource(this, track.uri)
                var title = track.name.substringBeforeLast('.').ifBlank { track.name }
                var artist = ""
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(this, track.uri)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                        ?.takeIf { it.isNotBlank() }?.let { title = it }
                    artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty()
                } catch (_: Exception) {
                    // Tags are optional; the file name is a fine title.
                } finally {
                    retriever.release()
                }
                ui.post {
                    if (stale()) {
                        source.close()
                        return@post
                    }
                    deck.load(source, title, artist, source.durationSec)
                    deck.setHotCues(cache.hotCues(track))
                    source.start()
                    loadedTracks[index] = track
                    panel.message = null
                }
                TrackAnalyzer.analyze(
                    this, track, cache, stale,
                    onWaveform = { waveform -> ui.post { if (!stale()) deck.waveform = waveform } },
                    onGrid = { grid -> ui.post { if (!stale()) deck.setGrid(grid.bpm, grid.anchorSec, grid.confidence) } }
                )
            } catch (e: Exception) {
                ui.post {
                    if (stale()) return@post
                    panel.message = "Cannot play ${track.name}"
                    panel.messageIsError = true
                }
            }
        }
    }

    private inner class TrackAdapter : BaseAdapter() {
        override fun getCount(): Int = visibleTracks.size
        override fun getItem(position: Int): Any = visibleTracks[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.row_track, parent, false)
            val track = visibleTracks[position]
            view.findViewById<TextView>(R.id.row_title).text =
                track.name.substringBeforeLast('.').ifBlank { track.name }
            val analysis = cache.get(track)
            view.findViewById<TextView>(R.id.row_info).text = if (analysis == null) "" else String.format(
                Locale.US, "%.1f BPM  ·  %d:%02d",
                analysis.bpm, analysis.durationSec.toInt() / 60, analysis.durationSec.toInt() % 60
            )
            view.findViewById<View>(R.id.row_load_a).setOnClickListener { loadFromRow(0, track) }
            view.findViewById<View>(R.id.row_load_b).setOnClickListener { loadFromRow(1, track) }
            view.setBackgroundColor(getColor(if (position == selectedTrack) R.color.panel_alt else R.color.bg))
            return view
        }
    }

    private fun loadFromRow(deck: Int, track: Track) {
        loadTrack(deck, track)
        showLibrary(false)
    }

    // ---------------------------------------------------------------------
    // Audio settings
    // ---------------------------------------------------------------------

    private fun showAudioSettings() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        fun slider(label: String, value: Float, onChange: (Float) -> Unit) {
            content.addView(TextView(this).apply {
                text = label
                setTextColor(getColor(R.color.text_dim))
                textSize = 11f
            })
            content.addView(FaderView(this).apply {
                accent = getColor(R.color.accent)
                show(value)
                this.onChange = onChange
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, pad * 2))
        }
        slider("Master level", engine.masterGain) { engine.masterGain = it }
        slider("Headphones level", engine.headphoneGain) { engine.headphoneGain = it }
        slider("Headphones mix: CUE ◄ ► MASTER", engine.headphoneMix) { engine.headphoneMix = it }
        content.addView(CheckBox(this).apply {
            text = "Split cue on a stereo output (master left, headphones right)"
            isChecked = engine.splitCue
            setOnCheckedChangeListener { _, checked ->
                engine.splitCue = checked
                preferences.edit().putBoolean(PREF_SPLIT, checked).apply()
            }
        })

        val devices = engine.outputs()
        val labels = devices.map { describe(it) } + "Phone default"
        AlertDialog.Builder(this)
            .setTitle("Audio output")
            .setItems(labels.toTypedArray()) { _, which -> engine.selectOutput(devices.getOrNull(which)) }
            .setView(content)
            .setPositiveButton("Done", null)
            .show()
    }

    private fun describe(device: AudioDeviceInfo): String {
        val kind = when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "headphones"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET ->
                if (AudioEngine.hasFourChannels(device)) "USB, master + headphones" else "USB, stereo"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth, latency!"
            else -> "output"
        }
        return "${device.productName} ($kind)"
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private companion object {
        const val REQ_FOLDER = 41
        const val PREFS = "flx4-preferences"
        const val PREF_FOLDER = "folder"
        const val PREF_SPLIT = "split_cue"
        const val PREF_ZOOM = "zoom"
        const val QUICK_JUMP_BEATS = 16.0
        val DECK_NAMES = arrayOf("A", "B")
        val FX_BEATS = doubleArrayOf(0.125, 0.25, 0.5, 0.75, 1.0, 2.0, 4.0, 8.0)
        val FX_BEAT_LABELS = arrayOf("1/8", "1/4", "1/2", "3/4", "1", "2", "4", "8")
        val FX_TARGET_LABELS = arrayOf("CH A", "CH B", "MASTER")
    }
}
