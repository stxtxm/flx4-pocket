package io.github.timotheegrollier.flx4pocket

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.RoundedCorner
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
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import kotlin.math.abs
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
    private lateinit var recButton: TextView
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
    private lateinit var libraryTarget: TextView
    private lateinit var panelArea: View
    private lateinit var foldable: List<View>
    private lateinit var panelToggles: List<TextView>

    private val tracks = ArrayList<Track>()
    private var visibleTracks: List<Track> = emptyList()
    private var selectedTrack = -1
    private lateinit var folderList: ListView
    private lateinit var libraryEmpty: View
    private var folderRows: List<FolderRow> = emptyList()
    private var selectedFolder = 0
    private val folderAdapter = FolderAdapter()

    /** The list the controller's BROWSE knob moves in: folders, or tracks (as in rekordbox). */
    private var browseFolders = false
    private var sortByBpm = false
    private lateinit var sortButton: TextView

    /** Playlists by name, in creation order: track URIs in play order. */
    private val playlists = LinkedHashMap<String, MutableList<String>>()
    private var waveShare = 1f / 3f

    /** Deck a tap on a track loads, when the library was opened from a deck; else -1. */
    private var loadTarget = -1
    private val trackAdapter = TrackAdapter()

    private val loadTokens = AtomicIntegerArray(2)

    /** Reads the tags and finds the keys of the library, one track at a time, off the audio path. */
    private val infoWorker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "library-info").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    private val libraryGeneration = AtomicInteger()
    private val rowsRefreshQueued = AtomicBoolean()
    private val loadedTracks = arrayOfNulls<Track>(2)

    /** Where each FLX4 tempo fader sits (-1..1, + at the bottom), NaN until it moves. */
    private val tempoFaders = doubleArrayOf(Double.NaN, Double.NaN)
    private var serviceRunning = false
    private var lastPlayedAt = 0L

    /** Controller LEDs and the background service, also while the screen is off. */
    private val ticker = object : Runnable {
        override fun run() {
            if (midi.connected) updateLeds()
            val now = SystemClock.uptimeMillis()
            if (busy) lastPlayedAt = now
            if (serviceRunning && now - lastPlayedAt > SERVICE_IDLE_MS) {
                serviceRunning = false
                stopService(Intent(this@MainActivity, PlaybackService::class.java))
            }
            ui.postDelayed(this, TICK_MS)
        }
    }
    /** Android 13+: how long a frame waits to be shown, which the waveforms draw ahead by. */
    private val frameLead = if (Build.VERSION.SDK_INT >= 33) FrameLead { lead -> waves.forEach { it.lead = lead } } else null
    private val taps = Array(2) { ArrayList<Long>() }
    private val lastLoadPress = LongArray(2)
    private val fxChannels = booleanArrayOf(true, true)
    private var fxBeatIndex = 2 // 1/2 beat
    private var midiName: String? = null
    private var frame = 0
    private var lastBackPress = 0L
    private var recordingName = ""

    private val preferences by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            engine.decks.forEach { it.pause() }
        }
    }

    private val focusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            // Otherwise Android ducks the whole master under every notification sound.
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC
        applyKeepScreenOn()
        applyOrientationLock()

        engine = AudioEngine(this)
        engine.splitCue = preferences.getBoolean(PREF_SPLIT, false)
        library = Library(this)
        cache = AnalysisCache(this)
        work = Executors.newFixedThreadPool(4)

        setContentView(R.layout.activity_main)
        fitDisplayShape()
        statusLabel = findViewById(R.id.status)
        masterVu = findViewById(R.id.master_vu)
        setupDecks()
        setupMixer()
        setupLibrary()
        setupPanels()
        findViewById<View>(R.id.btn_library).setOnClickListener { showLibrary(!libraryVisible) }
        findViewById<View>(R.id.btn_audio).setOnClickListener { showAudioSettings() }
        findViewById<View>(R.id.btn_settings).setOnClickListener { showSettings() }
        recButton = findViewById(R.id.btn_rec)
        recButton.setOnClickListener { toggleRecording() }
        updates = UpdateUi(this, findViewById(R.id.btn_update), work) { busy }
        updates.start()

        engine.start()
        midi = Flx4Midi(this, this)
        midi.start()
        ui.post(ticker)
        getSystemService(AudioManager::class.java).requestAudioFocus(focusRequest)

        // Up to 0.4 the library was one folder; it becomes the first of the list.
        preferences.getString(PREF_FOLDER, null)?.let { old ->
            preferences.edit().putStringSet(PREF_FOLDERS, sources(PREF_FOLDERS) + old).remove(PREF_FOLDER).apply()
        }
        if (!hasMusic()) {
            libraryInfo.text = "Add your music"
            showLibrary(true)
        }
    }

    override fun onResume() {
        super.onResume()
        applyOrientationLock()
        applyKeepScreenOn()
        updates.onResume()
        // Back in the app: a track deleted from the phone meanwhile drops out of the list.
        if (hasMusic()) scanLibrary()
        Choreographer.getInstance().postFrameCallback(this)
        frameLead?.start()
    }

    override fun onPause() {
        Choreographer.getInstance().removeFrameCallback(this)
        frameLead?.stop()
        super.onPause()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        stopService(Intent(this, PlaybackService::class.java))
        updates.stop()
        midi.close()
        engine.close()
        work.shutdownNow()
        getSystemService(AudioManager::class.java).abandonAudioFocusRequest(focusRequest)
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

    /**
     * Rounded display corners and the camera hole hide what sits under them. From Android 12
     * the screen knows their shape: it fills the display and keeps its content clear of the
     * hole, of any system bar still showing and, at both ends of its long sides, of 60 % of the
     * corner radius. There a corner's arc is under 4 dp deep: less than the gap the controls
     * already keep from the edge, so the long sides give nothing up.
     */
    private fun fitDisplayShape() {
        if (Build.VERSION.SDK_INT < 31) return // no corner shape to read: Android's own fitting stays
        window.setDecorFitsSystemWindows(false)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { view, insets ->
            val edge = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            fun corners(a: Int, b: Int) =
                (maxOf(insets.getRoundedCorner(a)?.radius ?: 0, insets.getRoundedCorner(b)?.radius ?: 0) * 0.6f).toInt()
            if (upright) {
                view.setPadding(
                    edge.left, maxOf(edge.top, corners(RoundedCorner.POSITION_TOP_LEFT, RoundedCorner.POSITION_TOP_RIGHT)),
                    edge.right, maxOf(edge.bottom, corners(RoundedCorner.POSITION_BOTTOM_LEFT, RoundedCorner.POSITION_BOTTOM_RIGHT))
                )
            } else {
                view.setPadding(
                    maxOf(edge.left, corners(RoundedCorner.POSITION_TOP_LEFT, RoundedCorner.POSITION_BOTTOM_LEFT)), edge.top,
                    maxOf(edge.right, corners(RoundedCorner.POSITION_TOP_RIGHT, RoundedCorner.POSITION_BOTTOM_RIGHT)), edge.bottom
                )
            }
            WindowInsets.CONSUMED
        }
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onBackPressed() {
        val now = SystemClock.uptimeMillis()
        when {
            libraryVisible -> showLibrary(false)
            // A stray back gesture must not end a set.
            busy && now - lastBackPress > 2500 -> {
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
        fxOn.onPress { engine.beatFx.enabled = !engine.beatFx.enabled }
        engine.beatFx.beats = FX_BEATS[fxBeatIndex]
    }

    private fun setupLibrary() {
        libraryPanel = findViewById(R.id.library_panel)
        libraryList = findViewById(R.id.library_list)
        libraryInfo = findViewById(R.id.library_info)
        librarySearch = findViewById(R.id.library_search)
        libraryTarget = findViewById(R.id.library_target)
        libraryEmpty = findViewById(R.id.library_empty)
        folderList = findViewById(R.id.library_folders)
        folderList.adapter = folderAdapter
        folderList.setOnItemClickListener { _, _, position, _ ->
            browseFolders = true
            selectFolder(position)
        }
        folderList.setOnItemLongClickListener { _, _, position, _ ->
            val row = folderRows.getOrNull(position)
            when {
                row == null -> Unit
                row.playlist != null -> confirmDeletePlaylist(row.playlist)
                row.path.isEmpty() -> Unit
                row.depth > 0 -> toast("Remove ${row.path.substringBefore('/')} to take it out of the library")
                else -> confirmRemove(row.label, tracks.filter { Library.inFolder(it.folder, row.path) })
            }
            true
        }
        libraryList.adapter = trackAdapter
        libraryList.setOnItemClickListener { _, _, position, _ ->
            val track = visibleTracks.getOrNull(position) ?: return@setOnItemClickListener
            browseFolders = false
            if (loadTarget >= 0) {
                loadFromRow(loadTarget, track)
            } else {
                selectedTrack = position // for the controller's LOAD buttons
                trackAdapter.notifyDataSetChanged()
                folderAdapter.notifyDataSetChanged()
            }
        }
        libraryList.setOnItemLongClickListener { _, _, position, _ ->
            visibleTracks.getOrNull(position)?.let(::trackMenu)
            true
        }
        librarySearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = filterTracks()
        })
        findViewById<View>(R.id.library_folder).setOnClickListener { chooseFolder() }
        findViewById<View>(R.id.library_files).setOnClickListener { chooseFiles() }
        findViewById<View>(R.id.library_playlist).setOnClickListener { newPlaylist(null) }
        sortButton = findViewById(R.id.library_sort)
        sortButton.setOnClickListener {
            sortByBpm = !sortByBpm
            filterTracks()
        }
        loadPlaylists()
        findViewById<View>(R.id.library_close).setOnClickListener { showLibrary(false) }
    }

    /** Deck A, mixer and deck B fold away from their toggles; remembered across launches. */
    private fun setupPanels() {
        panelArea = findViewById(R.id.panels)
        foldable = listOf(findViewById(R.id.deck_a), findViewById(R.id.mixer), findViewById(R.id.deck_b))
        panelToggles = listOf(findViewById(R.id.toggle_deck_a), findViewById(R.id.toggle_mixer), findViewById(R.id.toggle_deck_b))
        foldable.forEachIndexed { i, panel ->
            panelToggles[i].setOnClickListener {
                val show = panel.visibility != View.VISIBLE
                panel.visibility = if (show) View.VISIBLE else View.GONE
                preferences.edit().putBoolean(scoped(PANEL_PREFS[i]), show).apply()
                layoutPanels()
            }
        }
        setupResize()
        applyOrientation()
    }

    private val upright: Boolean get() =
        !preferences.getBoolean(PREF_DISABLE_UPRIGHT, false) &&
            resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    /** Persistent vertical-mode setting: when off, always stay in landscape, even coming back to the app. */
    private fun applyOrientationLock() {
        requestedOrientation = if (preferences.getBoolean(PREF_DISABLE_UPRIGHT, false))
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else
            ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
    }

    private fun applyKeepScreenOn() {
        if (preferences.getBoolean(PREF_KEEP_SCREEN_ON, true)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** Held upright, the screen remembers its own panels and waveform height; landscape keeps the keys it always had. */
    private fun scoped(key: String) = if (upright) "$key.upright" else key

    /** The activity is never recreated (it owns the audio engine): a rotation only restacks the screen. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientation()
    }

    /**
     * Held upright, the panels stack instead of sitting side by side, so each gets the
     * full width, and the two bars that need more than that width take two rows.
     */
    private fun applyOrientation() {
        val stacked = upright
        findViewById<LinearLayout>(R.id.panel_row).stack(stacked)
        findViewById<LinearLayout>(R.id.library_body).stack(stacked)
        findViewById<LinearLayout>(R.id.library_header).stack(stacked)
        // The status line leaves the button row for a row of its own under it, and takes the
        // version button along: UPDATE 0.6.1 would not fit in the button row.
        val buttons = findViewById<LinearLayout>(R.id.bar_row)
        val status = findViewById<LinearLayout>(R.id.bar_status)
        val update = findViewById<View>(R.id.btn_update)
        (status.parent as ViewGroup).removeView(status)
        (update.parent as ViewGroup).removeView(update)
        (status.layoutParams as LinearLayout.LayoutParams).run {
            width = if (stacked) ViewGroup.LayoutParams.MATCH_PARENT else 0
            height = if (stacked) buttons.layoutParams.height else ViewGroup.LayoutParams.MATCH_PARENT
            weight = if (stacked) 0f else 1f
        }
        if (stacked) findViewById<LinearLayout>(R.id.control_bar).addView(status)
        else buttons.addView(status, panelToggles.size)
        (if (stacked) status else buttons).addView(update)
        findViewById<View>(R.id.bar_gap).visibility = if (stacked) View.VISIBLE else View.GONE
        // Three panels are too many for one column: there the mixer starts folded.
        foldable.forEachIndexed { i, panel ->
            val open = preferences.getBoolean(scoped(PANEL_PREFS[i]), !stacked || i != 1) // 1: the mixer
            panel.visibility = if (open) View.VISIBLE else View.GONE
        }
        applyWaveShare(findViewById(R.id.waves), preferences.getFloat(scoped(PREF_WAVE_SHARE), if (stacked) 0.22f else 1f / 3f))
        layoutPanels()
        findViewById<View>(R.id.root).requestApplyInsets() // the long sides are now the other pair
    }

    /** Turns a row into a column, or back: every child trades its width for its height. */
    private fun LinearLayout.stack(vertical: Boolean) {
        if ((orientation == LinearLayout.VERTICAL) == vertical) return
        orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.layoutParams = child.layoutParams.apply { width = height.also { height = width } }
        }
    }

    /** Dragging the bar under the waveforms (outside its buttons) moves the line between waveforms and panels. */
    @SuppressLint("ClickableViewAccessibility") // a drag; the panel toggles are the accessible way to resize
    private fun setupResize() {
        val waveArea = findViewById<View>(R.id.waves)
        var startY = 0f
        var startShare = 0f
        findViewById<View>(R.id.control_bar).setOnTouchListener { _, event ->
            val total = waveArea.height + panelArea.height
            if (panelArea.visibility != View.VISIBLE || total <= 0) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    startShare = waveArea.height.toFloat() / total
                }
                MotionEvent.ACTION_MOVE ->
                    applyWaveShare(waveArea, (startShare + (event.rawY - startY) / total).coerceIn(0.15f, 0.85f))
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    preferences.edit().putFloat(scoped(PREF_WAVE_SHARE), waveShare).apply()
            }
            true
        }
    }

    private fun applyWaveShare(waveArea: View, share: Float) {
        waveShare = share
        (waveArea.layoutParams as LinearLayout.LayoutParams).weight = share
        (panelArea.layoutParams as LinearLayout.LayoutParams).weight = 1f - share
        waveArea.requestLayout()
    }

    /** With every panel folded and the library closed, the waveforms get the whole screen. */
    private fun layoutPanels() {
        val accents = intArrayOf(R.color.accent_a, R.color.text, R.color.accent_b)
        var anyOpen = false
        foldable.forEachIndexed { i, panel ->
            val open = panel.visibility == View.VISIBLE
            anyOpen = anyOpen || open
            panelToggles[i].isSelected = open
            if (open) panelToggles[i].tint(getColor(accents[i]), getColor(R.color.on_accent))
            else panelToggles[i].tint(getColor(R.color.panel_alt), getColor(R.color.text_dim))
        }
        panelArea.visibility = if (anyOpen || libraryVisible) View.VISIBLE else View.GONE
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

        // Started while the screen is in front (Android refuses it from the background), so a
        // set keeps playing when the screen goes off or another app opens. The ticker stops it.
        if (!serviceRunning && busy) {
            lastPlayedAt = SystemClock.uptimeMillis()
            serviceRunning = runCatching {
                startForegroundService(Intent(this, PlaybackService::class.java))
            }.isSuccess
        }
        panels.forEachIndexed { i, panel ->
            panel.refresh(engine.masterDeckIndex == i, takeover(i), engine.decks[1 - i].syncEnabled)
        }
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
        waves.forEach { it.latency = engine.latencyMs / 1000.0 }
        val tape = engine.recorder
        recButton.show(if (tape == null) "REC" else "● " + minutes(tape.frames / tape.sampleRate))
        if (tape == null) recButton.tint(idle, text) else recButton.tint(getColor(R.color.danger), text)
        if (tape?.error != null) toggleRecording() // it ended by itself: stop and say why
        updateStatus()
    }

    /** A deck plays or the mix is being recorded: nothing may stop the app unasked. */
    private val busy: Boolean get() = engine.decks.any { it.playing } || engine.recorder.let { it != null && it.error == null }

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
        // The rate and "fast" (Android's low-latency path) say what the measured delay is made of.
        val path = "${(engine.sampleRate / 1000.0).toString().removeSuffix(".0")} kHz${if (engine.lowLatency) " fast" else ""}"
        val latency = (if (engine.latencyMs > 0) "  ·  ${engine.latencyMs} ms" else "") +
            if (engine.dropouts > 0) "  ·  ${engine.dropouts} dropouts" else ""
        statusLabel.show("${midiName ?: getString(R.string.status_no_controller)}  ·  $output ($layout)  ·  $path$latency")
        statusLabel.setTextColor(getColor(if (midiName != null) R.color.ok else R.color.text_dim))
    }

    private fun updateLeds() {
        for (index in 0..1) {
            val d = engine.decks[index]
            val mode = d.padMode
            midi.setLed(MidiMap.CH_LOADED, index, d.hasTrack())
            midi.setLed(index, MidiMap.NOTE_PLAY, d.playing)
            midi.setLed(index, MidiMap.NOTE_CUE, d.hasTrack() && !d.playing)
            // Lit on the master too, while the other deck follows it, as on rekordbox.
            midi.setLed(index, MidiMap.NOTE_SYNC, d.syncEnabled || (engine.masterDeckIndex == index && engine.decks[1 - index].syncEnabled))
            midi.setLed(index, MidiMap.NOTE_CUE_CHANNEL, d.cueEnabled)
            // IN or OUT blinks while the jog moves its loop point, as with rekordbox.
            val blink = SystemClock.uptimeMillis() / 250 % 2 == 0L
            midi.setLed(index, MidiMap.NOTE_LOOP_IN, if (d.loopAdjust == Deck.ADJUST_IN) blink else d.loopInSec >= 0.0)
            midi.setLed(index, MidiMap.NOTE_LOOP_OUT, if (d.loopAdjust == Deck.ADJUST_OUT) blink else d.loopActive)
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
        if (engine.decks[deck].hasTrack()) engine.decks[deck].togglePlay() else onLoadRequest(deck)
        waves.getOrNull(deck)?.invalidate()
    }

    /** An empty deck was tapped: open the library for it, or the folder picker on first use. */
    override fun onLoadRequest(deck: Int) {
        showLibrary(true, deck)
        if (!hasMusic()) chooseFolder()
    }

    override fun onSync(deck: Int) {
        val d = engine.decks[deck]
        val other = engine.decks[1 - deck]
        when {
            d.syncEnabled -> d.releaseSync()
            !d.grid.valid -> toast("This deck has no BPM yet — wait for the analysis or TAP the tempo")
            !other.grid.valid -> toast("The other deck has no BPM yet")
            else -> {
                // SYNC makes this deck the follower, also when it is the one on air: the other
                // deck becomes the master. That is how rekordbox arms a pair in either start
                // order, and the button lights as soon as it is pressed instead of only after
                // the other deck syncs. A long press still hands the master role over by hand.
                // Engage first: in the window before this, the mixer would see a playing deck
                // that is not synced under a stopped master and hand the role back to it.
                if (d.engageSync(other)) engine.setMasterDeck(1 - deck)
                else toast("This deck cannot sync — load a track")
            }
        }
        updateLeds() // the button answers at once, not on the next screen refresh
    }

    override fun onMaster(deck: Int) {
        engine.setMasterDeck(deck)
        toast("Deck ${DECK_NAMES[deck]} is the sync master")
        updateLeds()
    }

    override fun onTap(deck: Int) {
        val now = SystemClock.elapsedRealtime()
        val times = taps[deck]
        if (times.isNotEmpty() && now - times.last() > 2200) times.clear()
        times.add(now)
        if (times.size > 8) times.removeAt(0)
        if (times.size < 3 || times.last() == times.first()) return
        var bpm = 60_000.0 * (times.size - 1) / (times.last() - times.first())
        while (bpm < 70.0) bpm *= 2.0
        while (bpm > 180.0) bpm /= 2.0
        engine.decks[deck].tapGrid(bpm, engine.latencyMs / 1000.0)
    }

    override fun onCuesChanged(deck: Int) {
        val track = loadedTracks[deck] ?: return
        cache.putHotCues(track, engine.decks[deck].hotCues)
        cache.putMemoryCues(track, engine.decks[deck].memoryCues)
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
        if (name != null) toast("$name connected") else tempoFaders.fill(Double.NaN)
    }

    // Flx4Midi calls these on its MIDI thread, the tests on the main one. What changes the sound is done
    // there at once, through the decks' own locks and volatile fields; anything that touches a View, the
    // LEDs or the activity's own state goes through onMain.

    /** Runs [block] on the main thread: at once when already there, else ahead of any pending layout. */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() === Looper.getMainLooper()) return block()
        queued.incrementAndGet()
        ui.sendMessage(Message.obtain(ui) { try { block() } finally { queued.decrementAndGet() } }.apply { isAsynchronous = true })
    }

    /** Controller actions still waiting for the main thread. */
    private val queued = AtomicInteger()

    /**
     * Runs [block] at once on the MIDI thread, unless an earlier press still waits for the main thread:
     * then it queues behind it, so SYNC then PLAY, or LOOP IN then the jog, act in the order they were made.
     */
    private inline fun direct(crossinline block: () -> Unit) = if (queued.get() == 0) block() else onMain { block() }

    override fun onButton(deck: Int, button: Flx4Button, down: Boolean) {
        val d = engine.decks.getOrNull(deck)
        when (button) {
            Flx4Button.CUE -> direct { if (down) d?.cueDown() else d?.cueUp() }
            Flx4Button.JOG_TOUCH -> direct { if (down) d?.scratchBegin() else d?.scratchEnd() }
            Flx4Button.PLAY -> if (down && d != null) direct {
                if (d.hasTrack()) d.togglePlay() else onMain { onLoadRequest(deck) }
            }
            Flx4Button.FX_CHANNEL -> onMain {
                // A three-position switch: CH1, CH2, or both for the master.
                fxChannels[deck] = down
                engine.fxTarget = when {
                    fxChannels[0] && !fxChannels[1] -> AudioEngine.FX_DECK_A
                    fxChannels[1] && !fxChannels[0] -> AudioEngine.FX_DECK_B
                    else -> AudioEngine.FX_MASTER
                }
            }
            else -> if (down) onMain { onPress(deck, d, button) }
        }
        onMain { answer(deck) }
    }

    /** The FLX4's LEDs answer a control at once, and the screen on its next frame, not on its next refresh. */
    private fun answer(deck: Int) {
        updateLeds()
        frame = 0
        waves.getOrNull(deck)?.invalidate()
    }

    private fun onPress(deck: Int, d: Deck?, button: Flx4Button) {
        when (button) {
            Flx4Button.TO_START -> d?.seekTo(0.0)
            Flx4Button.SYNC -> onSync(deck)
            Flx4Button.MASTER -> onMaster(deck)
            Flx4Button.TEMPO_RANGE -> d?.cycleTempoRange()
            // The loop section as rekordbox maps it on the FLX4.
            Flx4Button.LOOP_IN -> d?.setLoopIn()
            Flx4Button.LOOP_OUT -> d?.setLoopOut()
            Flx4Button.FOUR_BEAT_EXIT -> d?.fourBeatLoopOrExit()
            Flx4Button.ACTIVE_LOOP -> d?.toggleLoop()
            // CUE/LOOP CALL: halve / double a loop, otherwise memory cues; SHIFT deletes / saves them.
            Flx4Button.CUE_LOOP_CALL_PREV -> d?.cueLoopCall(-1)
            Flx4Button.CUE_LOOP_CALL_NEXT -> d?.cueLoopCall(1)
            Flx4Button.MEMORY_DELETE -> d?.let {
                it.deleteMemoryCue()
                onCuesChanged(deck)
            }
            Flx4Button.MEMORY_SAVE -> d?.let {
                it.saveMemoryCue()
                onCuesChanged(deck)
            }
            Flx4Button.PFL -> d?.let { it.cueEnabled = !it.cueEnabled }
            Flx4Button.TAP -> onTap(deck) // rekordbox: SHIFT + headphones CUE taps the tempo
            Flx4Button.LOAD -> {
                // Pressed twice: instant doubles, as in rekordbox.
                val now = SystemClock.uptimeMillis()
                if (now - lastLoadPress[deck] < DOUBLE_PRESS_MS) instantDouble(deck) else loadSelected(deck)
                lastLoadPress[deck] = now
            }
            // rekordbox for Android: SHIFT + LOAD 1 shows related tracks, SHIFT + LOAD 2 the browser.
            Flx4Button.SHIFT_LOAD -> if (deck == 0) showRelated() else showLibrary(!libraryVisible)
            // rekordbox: BROWSE press moves between the folders and the tracks.
            Flx4Button.BROWSE_PRESS -> if (libraryVisible) switchBrowseList() else showLibrary(true)
            Flx4Button.BROWSE_SHIFT_PRESS -> showLibrary(!libraryVisible)
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
            Flx4Knob.TEMPO -> if (d != null) onMain { tempoFader(deck, d, 1.0 - Math.round(value * 16383.0) / 8192.0) } // code 8192: exactly 0 %, as in Mixxx
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

    /**
     * Soft takeover: after a sync, a touch-screen change or a reconnection the
     * hardware fader only drives the tempo again once it reaches the speed the
     * deck plays at, so the tempo never jumps.
     */
    private fun tempoFader(index: Int, d: Deck, position: Double) {
        val current = d.tempoPosition
        val last = tempoFaders[index]
        tempoFaders[index] = position
        if (d.syncEnabled) return // as on Pioneer gear, a synced deck ignores its fader: SYNC off hands the tempo back
        val inControl = !last.isNaN() && abs(last - current) < TAKEOVER
        val crossed = !last.isNaN() && (last - current) * (position - current) <= 0.0
        if (inControl || crossed || abs(position - current) < TAKEOVER) d.setTempoNormalized(position)
    }

    /** Which way the FLX4 tempo fader must move to take over: -1 up, +1 down, 0 in control or unknown. */
    private fun takeover(index: Int): Int {
        val hardware = tempoFaders[index]
        val current = engine.decks[index].tempoPosition
        return when {
            hardware.isNaN() || engine.decks[index].syncEnabled || abs(hardware - current) < TAKEOVER -> 0
            hardware < current -> 1
            else -> -1
        }
    }

    override fun onJog(deck: Int, jog: Flx4Jog, delta: Int) {
        val d = engine.decks[deck]
        direct {
        when (jog) {
            Flx4Jog.SCRATCH -> if (d.scratching) d.scratchMove(delta * Deck.JOG_TICK_SECONDS) else d.bend(delta)
            Flx4Jog.BEND -> d.bend(delta)
            Flx4Jog.SEARCH -> d.seekBy(delta * 0.2)
        }
        }
        // Any thread; a paused deck's waveform otherwise follows the jog 20 times a second.
        waves[deck].postInvalidateOnAnimation()
    }

    override fun onPadMode(deck: Int, mode: Int) = onMain {
        when (mode) {
            Deck.PAD_SAMPLER -> toast(getString(R.string.not_supported, "Sampler"))
            Deck.PAD_KEYBOARD -> toast(getString(R.string.not_supported, "Keyboard mode"))
            Deck.PAD_KEY_SHIFT -> toast(getString(R.string.not_supported, "Key Shift"))
            else -> engine.decks[deck].padMode = mode
        }
        answer(deck)
    }

    override fun onPad(deck: Int, mode: Int, pad: Int, shift: Boolean, down: Boolean) {
        val d = engine.decks[deck]
        direct {
        if (down) {
            // The pads play the FLX4's own mode, which it keeps across an app restart: its LEDs follow it.
            if (mode != d.padMode && mode != Deck.PAD_SAMPLER && mode != Deck.PAD_KEYBOARD && mode != Deck.PAD_KEY_SHIFT) d.padMode = mode
            d.padDown(mode, pad, shift)
        } else {
            d.padUp(mode, pad)
        }
        }
        onMain {
            if (down && mode == Deck.PAD_HOT_CUE) onCuesChanged(deck)
            answer(deck)
        }
    }

    override fun onBrowse(delta: Int, shift: Boolean) = onMain { browse(delta, shift) }

    private fun browse(delta: Int, shift: Boolean) {
        if (!libraryVisible) {
            // The first click only shows where the selection is.
            showLibrary(true)
            if (selectedTrack >= 0) libraryList.setSelection(selectedTrack)
            return
        }
        val step = delta * if (shift) 10 else 1
        if (browseFolders) {
            if (folderRows.isEmpty()) return
            selectFolder((selectedFolder + step).coerceIn(0, folderRows.size - 1))
            folderList.smoothScrollToPosition(selectedFolder)
            return
        }
        if (visibleTracks.isEmpty()) return
        selectedTrack = (selectedTrack + step).coerceIn(0, visibleTracks.size - 1)
        trackAdapter.notifyDataSetChanged()
        libraryList.smoothScrollToPosition(selectedTrack)
    }

    private fun switchBrowseList() {
        browseFolders = !browseFolders
        folderAdapter.notifyDataSetChanged()
        trackAdapter.notifyDataSetChanged()
    }

    // ---------------------------------------------------------------------
    // Library
    // ---------------------------------------------------------------------

    private val libraryVisible: Boolean get() = libraryPanel.visibility == View.VISIBLE

    private fun showLibrary(show: Boolean, target: Int = -1) {
        loadTarget = if (show) target else -1
        libraryPanel.visibility = if (show) View.VISIBLE else View.GONE
        waves.forEach { it.scratchable = !show }
        libraryTarget.visibility = if (loadTarget >= 0) View.VISIBLE else View.GONE
        if (loadTarget >= 0) {
            libraryTarget.text = "LOAD TO ${DECK_NAMES[loadTarget]}"
            libraryTarget.tint(getColor(if (loadTarget == 0) R.color.accent_a else R.color.accent_b), getColor(R.color.on_accent))
        }
        if (!show) {
            getSystemService(InputMethodManager::class.java)
                .hideSoftInputFromWindow(librarySearch.windowToken, 0)
            librarySearch.clearFocus()
        }
        layoutPanels() // the library needs the panel area, even with every panel folded
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

    /** Single audio files, several at once; they show under "Added tracks". */
    private fun chooseFiles() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("audio/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_FILES)
        } catch (e: Exception) {
            toast("No file picker available: ${e.message}")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_RECORDING_TREE) {
            if (resultCode != RESULT_OK || data?.data == null) return
            val tree = data.data!!
            runCatching {
                contentResolver.takePersistableUriPermission(
                    tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            preferences.edit().putString(PREF_RECORDING_TREE, tree.toString()).apply()
            return
        }
        val key = when (requestCode) {
            REQ_FOLDER -> PREF_FOLDERS
            REQ_FILES -> PREF_FILES
            else -> return
        }
        if (resultCode != RESULT_OK || data == null) return
        val picked = data.clipData?.let { clip -> List(clip.itemCount) { clip.getItemAt(it).uri } }
            ?: listOfNotNull(data.data)
        // Without the persisted grant the music still plays for this session.
        picked.forEach {
            runCatching { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        preferences.edit().putStringSet(key, sources(key) + picked.map { it.toString() }).apply()
        scanLibrary()
    }

    /** Added folder trees ([PREF_FOLDERS]) or files ([PREF_FILES]), as URI strings. */
    private fun sources(key: String): Set<String> = preferences.getStringSet(key, null) ?: emptySet()

    private fun hasMusic(): Boolean = sources(PREF_FOLDERS).isNotEmpty() || sources(PREF_FILES).isNotEmpty()

    /** Takes what brought [found] into the library out of it, after a confirmation; the files stay. */
    private fun confirmRemove(name: String, found: List<Track>) {
        val gone = found.mapNotNull { it.source?.toString() }.toSet()
        if (gone.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Remove $name from the library?")
            .setMessage("The files stay on the phone.")
            .setPositiveButton("Remove") { _, _ ->
                preferences.edit()
                    .putStringSet(PREF_FOLDERS, sources(PREF_FOLDERS) - gone)
                    .putStringSet(PREF_FILES, sources(PREF_FILES) - gone)
                    .putStringSet(PREF_HIDDEN, sources(PREF_HIDDEN).filterNot { h -> gone.any { h.startsWith("$it/") } }.toSet())
                    .apply()
                gone.forEach {
                    runCatching { contentResolver.releasePersistableUriPermission(Uri.parse(it), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                }
                scanLibrary()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Hides one track of an added folder from the library; the file and its folder stay. */
    private fun confirmHide(track: Track) {
        AlertDialog.Builder(this)
            .setTitle("Remove ${track.name} from the library?")
            .setMessage("The file stays on the phone.")
            .setPositiveButton("Remove") { _, _ ->
                preferences.edit().putStringSet(PREF_HIDDEN, sources(PREF_HIDDEN) + track.uri.toString()).apply()
                scanLibrary()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun scanLibrary() {
        libraryInfo.text = "Scanning…"
        val folders = sources(PREF_FOLDERS).map(Uri::parse)
        val files = sources(PREF_FILES).map(Uri::parse)
        val hidden = sources(PREF_HIDDEN)
        work.execute {
            val found = try {
                library.scan(folders, files).filter { it.uri.toString() !in hidden }
            } catch (_: Exception) {
                emptyList()
            }
            ui.post { setLibrary(found) }
        }
    }

    /** Shows [found] as the library. */
    internal fun setLibrary(found: List<Track>) { // internal: the UI test fills the library directly
        tracks.clear()
        tracks.addAll(found)
        refreshFolders()
        readLibrary()
    }

    /**
     * Reads the tags of every track, then finds its key. A key is not decoded
     * while a deck plays: decoding a whole track would starve the audio thread.
     */
    private fun readLibrary() {
        val generation = libraryGeneration.incrementAndGet()
        val snapshot = tracks.toList()
        val current = { libraryGeneration.get() == generation }
        val playing = { engine.decks.any { it.playing } }
        infoWorker.execute {
            for (track in snapshot) {
                if (!current()) return@execute
                if (cache.info(track) == null) {
                    cache.putInfo(track, TrackInfo.read(this, track.uri, track.name))
                    refreshRows(generation)
                }
            }
            for (track in snapshot) {
                while (current() && !cache.keyAnalysed(track)) {
                    if (playing()) {
                        Thread.sleep(1000)
                        continue
                    }
                    // Null when stopped part way: the loop tries again once the decks are quiet.
                    val analysis = MusicalKeys.analyze(this, track.uri) { !current() || playing() } ?: continue
                    cache.putMusicalKey(track, analysis.key)
                    ui.post {
                        engine.decks.forEachIndexed { index, deck ->
                            if (loadedTracks[index] == track) deck.setKey(analysis.key)
                        }
                    }
                    refreshRows(generation)
                }
                if (!current()) return@execute
            }
        }
    }

    /** Redraws the rows once per burst of updates from [readLibrary], on the UI thread. */
    private fun refreshRows(generation: Int) {
        if (!rowsRefreshQueued.compareAndSet(false, true)) return
        ui.post {
            rowsRefreshQueued.set(false)
            if (libraryGeneration.get() != generation) return@post
            if (librarySearch.text.isNullOrEmpty()) {
                trackAdapter.notifyDataSetChanged()
            } else {
                // A search may match the new tags: filter again, keeping the cursor on the same track.
                val kept = visibleTracks.getOrNull(selectedTrack)
                filterTracks()
                if (visibleTracks.isNotEmpty()) selectedTrack = visibleTracks.indexOf(kept).coerceAtLeast(0)
                trackAdapter.notifyDataSetChanged()
            }
        }
    }

    /** Folders, then playlists; stays on the row that was selected if it still exists. */
    private fun refreshFolders() {
        val kept = folderRows.getOrNull(selectedFolder)
        val known = tracks.mapTo(HashSet()) { it.uri.toString() }
        folderRows = Library.folderRows(tracks.map { it.folder }, "All tracks") +
            playlists.map { (name, uris) -> FolderRow("", "♫ $name", 0, uris.count { it in known }, name) }
        selectedFolder = folderRows.indexOfFirst { it.path == kept?.path && it.playlist == kept?.playlist }
            .coerceAtLeast(0)
        folderAdapter.notifyDataSetChanged()
        filterTracks()
    }

    /** The search reads the file name and the tags: title, artist, album and genre. */
    private fun matchesSearch(track: Track, query: String): Boolean {
        if (query.isEmpty() || track.name.contains(query, ignoreCase = true)) return true
        val info = cache.info(track) ?: return false
        return listOf(info.title, info.artist, info.album, info.genre).any { it.contains(query, ignoreCase = true) }
    }

    private fun bpmOf(track: Track): Double = cache.get(track)?.bpm ?: Double.MAX_VALUE

    /** Long-press on a track: playlists, and removal for a track added on its own. */
    private fun trackMenu(track: Track) {
        val current = folderRows.getOrNull(selectedFolder)?.playlist
        val actions = ArrayList<Pair<String, () -> Unit>>()
        playlists.keys.filter { it != current }.forEach { name -> actions.add("Add to ♫ $name" to { addToPlaylist(name, track) }) }
        actions.add("Add to a new playlist…" to { newPlaylist(track) })
        if (current != null) {
            actions.add("Remove from ♫ $current" to {
                playlists[current]?.remove(track.uri.toString())
                savePlaylists()
            })
        }
        val analysis = cache.get(track)
        if (analysis != null) {
            // Half and double are the same beat: the analyser can read either, and rekordbox lets
            // the DJ settle it. The deck playing the track takes the new grid at once.
            if (analysis.bpm / 2.0 >= 40.0) {
                actions.add("Halve the BPM (${bpmLabel(analysis.bpm / 2.0)})" to { setTrackBpm(track, analysis, 0.5) })
            }
            if (analysis.bpm * 2.0 <= 300.0) {
                actions.add("Double the BPM (${bpmLabel(analysis.bpm * 2.0)})" to { setTrackBpm(track, analysis, 2.0) })
            }
        }
        if (track.folder == Library.ADDED_FILES) actions.add("Remove from the library…" to { confirmRemove(track.name, listOf(track)) })
        else actions.add("Remove from the library…" to { confirmHide(track) })
        AlertDialog.Builder(this)
            .setTitle(track.name.substringBeforeLast('.').ifBlank { track.name })
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    /** Rewrites the grid of [track] in the cache at another octave, tab and loaded deck included. */
    private fun setTrackBpm(track: Track, analysis: BpmAnalyzer.Result, factor: Double) {
        val result = BpmAnalyzer.Result(
            bpm = analysis.bpm * factor,
            anchorSec = analysis.anchorSec, // still a beat: half-time keeps every other one
            confidence = analysis.confidence,
            durationSec = analysis.durationSec
        )
        cache.put(track, result)
        loadedTracks.forEachIndexed { index, loaded ->
            if (loaded == track) engine.decks[index].setGrid(result.bpm, result.anchorSec, result.confidence)
        }
        filterTracks()
        toast("BPM set to ${bpmLabel(result.bpm)}")
    }

    private fun bpmLabel(bpm: Double): String = String.format(Locale.US, "%.1f", bpm)

    internal fun addToPlaylist(name: String, track: Track) { // internal: the UI test builds a playlist
        val uris = playlists.getOrPut(name) { ArrayList() }
        if (track.uri.toString() !in uris) uris.add(track.uri.toString())
        savePlaylists()
        toast("Added to ♫ $name")
    }

    /** Asks for a name; [track] goes in when there is one. */
    private fun newPlaylist(track: Track?) {
        val input = EditText(this).apply {
            hint = "Playlist name"
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("New playlist")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val base = input.text.toString().trim().ifEmpty { "Playlist" }
                var name = base
                var n = 2
                while (playlists.containsKey(name)) name = "$base ${n++}"
                playlists[name] = ArrayList()
                if (track != null) addToPlaylist(name, track) else savePlaylists()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDeletePlaylist(name: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete the playlist $name?")
            .setMessage("Its tracks stay in the library.")
            .setPositiveButton("Delete") { _, _ ->
                playlists.remove(name)
                savePlaylists()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun loadPlaylists() {
        val json = runCatching { JSONObject(preferences.getString(PREF_PLAYLISTS, null) ?: return) }.getOrNull() ?: return
        for (name in json.keys()) {
            val uris = json.optJSONArray(name) ?: continue
            playlists[name] = MutableList(uris.length()) { uris.getString(it) }
        }
    }

    private fun savePlaylists() {
        val json = JSONObject()
        playlists.forEach { (name, uris) -> json.put(name, JSONArray(uris)) }
        preferences.edit().putString(PREF_PLAYLISTS, json.toString()).apply()
        refreshFolders()
    }

    private fun selectFolder(position: Int) {
        if (position !in folderRows.indices) return
        selectedFolder = position
        folderAdapter.notifyDataSetChanged()
        filterTracks()
    }

    private fun filterTracks() {
        val query = librarySearch.text.toString().trim()
        val row = folderRows.getOrNull(selectedFolder)
        val list = row?.playlist?.let { name ->
            val byUri = tracks.associateBy { it.uri.toString() }
            playlists[name].orEmpty().mapNotNull { byUri[it] } // in play order
        } ?: tracks.filter { Library.inFolder(it.folder, row?.path.orEmpty()) }
        visibleTracks = list.filter { matchesSearch(it, query) }
        if (sortByBpm) visibleTracks = visibleTracks.sortedBy(::bpmOf) // unanalysed tracks last
        if (sortByBpm) sortButton.tint(getColor(R.color.accent), getColor(R.color.on_accent))
        else sortButton.tint(getColor(R.color.panel_alt), getColor(R.color.text))
        selectedTrack = if (visibleTracks.isEmpty()) -1 else 0
        trackAdapter.notifyDataSetChanged()
        libraryEmpty.visibility = if (tracks.isEmpty()) View.VISIBLE else View.GONE
        libraryInfo.text = when {
            tracks.isEmpty() -> "No music yet"
            visibleTracks.size == tracks.size -> "${tracks.size} tracks"
            else -> "${visibleTracks.size} of ${tracks.size}"
        }
    }

    /**
     * LOAD pressed twice: the other deck's track, playing along with it from the
     * same place at the same speed (rekordbox instant doubles).
     */
    private fun instantDouble(index: Int) {
        val other = engine.decks[1 - index]
        val track = loadedTracks[1 - index]
        if (track == null) return toast("Deck ${DECK_NAMES[1 - index]} has no track to double")
        if (engine.decks[index].playing) return toast("Deck ${DECK_NAMES[index]} is playing: pause it to load another track")
        showLibrary(false)
        loadTrack(index, track) { deck ->
            deck.playAlong(other)
            // shortcut: one realignment once the decoder has caught up; a decoded buffer shared by both decks would make it exact.
            ui.postDelayed({ if (loadedTracks[index] === track && deck.playing && other.playing) deck.playAlong(other) }, REALIGN_MS)
        }
    }

    /** SHIFT + LOAD 1: the library sorted by BPM, at the tempo of the master deck (a simple "related tracks"). */
    private fun showRelated() {
        sortByBpm = true
        showLibrary(true)
        filterTracks()
        val bpm = engine.decks[engine.masterDeckIndex].effectiveBpm()
        val near = visibleTracks.indexOfFirst { bpmOf(it) >= bpm }
        if (bpm > 0.0 && visibleTracks.isNotEmpty()) {
            selectedTrack = if (near >= 0) near else visibleTracks.lastIndex
            browseFolders = false
            trackAdapter.notifyDataSetChanged()
            libraryList.setSelection(selectedTrack)
        }
    }

    /** Controller LOAD: with the library closed it opens it first, so nothing loads unseen. */
    private fun loadSelected(deck: Int) {
        val track = visibleTracks.getOrNull(selectedTrack)
        if (!libraryVisible || track == null) showLibrary(true, deck) else loadFromRow(deck, track)
    }

    private fun loadTrack(index: Int, track: Track, onLoaded: (Deck) -> Unit = {}) {
        val token = loadTokens.incrementAndGet(index)
        val deck = engine.decks[index]
        val panel = panels[index]
        val stale = { loadTokens.get(index) != token }
        deck.clear()
        midi.setLed(MidiMap.CH_LOADED, index, false) // the jog ring replays its load animation
        loadedTracks[index] = null
        panel.message = "Loading ${track.name}…"
        panel.messageIsError = false
        work.execute {
            try {
                val source = AudioSource(this, track.uri)
                val info = cache.info(track)
                    ?: TrackInfo.read(this, track.uri, track.name).also { cache.putInfo(track, it) }
                ui.post {
                    if (stale()) {
                        source.close()
                        return@post
                    }
                    deck.load(source, info.title, info.artist, source.durationSec)
                    deck.setKey(cache.musicalKey(track))
                    deck.setHotCues(cache.hotCues(track))
                    deck.setMemoryCues(cache.memoryCues(track))
                    source.start()
                    loadedTracks[index] = track
                    panel.message = null
                    onLoaded(deck)
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
            val info = cache.info(track)
            view.findViewById<TextView>(R.id.row_title).text =
                info?.title ?: track.name.substringBeforeLast('.').ifBlank { track.name }
            val seconds = info?.durationSec?.takeIf { it > 0.0 } ?: cache.get(track)?.durationSec ?: 0.0
            view.findViewById<TextView>(R.id.row_info).text = listOfNotNull(
                info?.artist?.takeIf { it.isNotBlank() },
                info?.album?.takeIf { it.isNotBlank() },
                if (seconds > 0.0) String.format(Locale.US, "%d:%02d", seconds.toInt() / 60, seconds.toInt() % 60) else null,
                info?.codec?.takeIf { it.isNotBlank() }
            ).joinToString("  ·  ")
            view.findViewById<TextView>(R.id.row_key).text = cache.musicalKey(track)?.camelot.orEmpty()
            val analysis = cache.get(track)
            view.findViewById<TextView>(R.id.row_bpm).text =
                if (analysis == null) "" else bpmLabel(analysis.bpm)
            view.findViewById<View>(R.id.row_load_a).setOnClickListener { loadFromRow(0, track) }
            view.findViewById<View>(R.id.row_load_b).setOnClickListener { loadFromRow(1, track) }
            // The row under the cursor stands out while BROWSE moves in the tracks.
            val cursor = when {
                position != selectedTrack -> R.color.bg
                browseFolders -> R.color.panel
                else -> R.color.panel_alt
            }
            view.setBackgroundColor(getColor(cursor))
            return view
        }
    }

    /** Folder column: indented like a tree, the selected folder in amber. */
    private inner class FolderAdapter : BaseAdapter() {
        override fun getCount(): Int = folderRows.size
        override fun getItem(position: Int): Any = folderRows[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val density = resources.displayMetrics.density
            val view = convertView as? TextView ?: TextView(this@MainActivity).apply {
                textSize = 15f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                minHeight = (44 * density).toInt()
            }
            val row = folderRows[position]
            val selected = position == selectedFolder
            view.setPadding(((10 + 14 * row.depth) * density).toInt(), 0, (8 * density).toInt(), 0)
            view.text = "${row.label}   ${row.count}"
            view.setTextColor(getColor(if (selected) R.color.accent else R.color.text))
            view.setBackgroundColor(getColor(if (selected && browseFolders) R.color.panel_alt else R.color.bg))
            return view
        }
    }

    internal fun loadFromRow(deck: Int, track: Track) { // internal: the UI test calls it
        if (engine.decks[deck].playing) {
            // Load lock: a mistaken tap must not replace the track on air.
            toast("Deck ${DECK_NAMES[deck]} is playing: pause it to load another track")
            return
        }
        loadTrack(deck, track)
        showLibrary(false)
    }

    // ---------------------------------------------------------------------
    // Recording
    // ---------------------------------------------------------------------

    /** REC: the master output goes to a WAV file in [RECORDINGS] until REC is pressed again. */
    private fun toggleRecording() {
        val done = engine.stopRecording()
        if (done == null) {
            recordingName = "FLX4 set " + SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US).format(Date())
            engine.startRecording(::newRecordingFile)
            return
        }
        val length = minutes(done.frames / done.sampleRate)
        val gaps = if (done.dropped > 0) ", with ${done.dropped} gaps (the storage was too slow)" else ""
        toast(
            when {
                done.error == null -> "Recorded $length to $recordings$gaps"
                done.frames == 0L -> "Cannot record: ${done.error}"
                else -> "Recording stopped: ${done.error}. $length kept in $recordings"
            }
        )
    }

    /** Where [newRecordingFile] puts them, as the Files app shows it. */
    private val recordings: String
        get() = preferences.getString(PREF_RECORDING_TREE, null)?.let(::recordingTreeLabel)
            ?: if (Build.VERSION.SDK_INT < 29) getExternalFilesDir(Environment.DIRECTORY_MUSIC)?.path ?: RECORDINGS else RECORDINGS

    private fun recordingTreeLabel(uriString: String): String = try {
        DocumentsContract.getTreeDocumentId(Uri.parse(uriString))
    } catch (_: Exception) {
        uriString
    }

    /** Lets the user pick any writable folder through Android's document picker; persisted across launches. */
    private fun chooseRecordingFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_RECORDING_TREE)
        } catch (e: Exception) {
            toast("No folder picker available: ${e.message}")
        }
    }

    /** A new WAV in the chosen recording folder; otherwise the default Music folder. */
    private fun newRecordingFile(part: Int): FileChannel {
        val name = recordingName + (if (part > 1) " part $part" else "") + ".wav"
        val customTree = preferences.getString(PREF_RECORDING_TREE, null)?.let(Uri::parse)
        if (customTree != null) {
            val doc = DocumentsContract.createDocument(contentResolver, customTree, "audio/x-wav", name)
                ?: throw IOException("Cannot create $name in ${recordingTreeLabel(customTree.toString())}")
            val pfd = contentResolver.openFileDescriptor(doc, "rw") ?: throw IOException("Cannot open $name")
            return ParcelFileDescriptor.AutoCloseOutputStream(pfd).channel
        }
        if (Build.VERSION.SDK_INT < 29) {
            return RandomAccessFile(File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), name), "rw").channel
        }
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/x-wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, RECORDINGS)
        }
        val uri = contentResolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IOException("Cannot create $name")
        return ParcelFileDescriptor.AutoCloseOutputStream(contentResolver.openFileDescriptor(uri, "rw")).channel
    }

    private fun minutes(seconds: Long): String = String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)

    // ---------------------------------------------------------------------
    // Settings
    // ---------------------------------------------------------------------

    /** One persistent settings menu for display, audio shortcuts and recording. */
    private fun showSettings() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad)
        }
        fun section(title: String) {
            content.addView(TextView(this).apply {
                text = title
                setTextColor(getColor(R.color.text))
                textSize = 15f
                setPadding(0, pad, 0, pad / 2)
            })
        }
        fun check(label: String, key: String, default: Boolean, onChange: (Boolean) -> Unit = {}) {
            content.addView(CheckBox(this).apply {
                text = label
                isChecked = preferences.getBoolean(key, default)
                setOnCheckedChangeListener { _, checked ->
                    preferences.edit().putBoolean(key, checked).apply()
                    onChange(checked)
                }
            })
        }
        fun slider(label: String, key: String, default: Float, min: Float, max: Float, scopedKey: Boolean = false, onChange: (Float) -> Unit) {
            val prefKey = if (scopedKey) scoped(key) else key
            val value = preferences.getFloat(prefKey, default).coerceIn(min, max)
            val title = TextView(this).apply {
                text = "$label: ${"%.2f".format(value)}"
                setTextColor(getColor(R.color.text_dim))
                textSize = 13f
            }
            content.addView(title)
            content.addView(SeekBar(this).apply {
                this.max = 1000
                progress = (((value - min) / (max - min)).coerceIn(0f, 1f) * 1000).roundToInt()
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        val v = min + (max - min) * progress / 1000f
                        title.text = "$label: ${"%.2f".format(v)}"
                        preferences.edit().putFloat(prefKey, v).apply()
                        onChange(v)
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar) {}
                })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        section("Affichage")
        check("Désactiver le mode vertical / verrouiller paysage", PREF_DISABLE_UPRIGHT, false) {
            applyOrientationLock()
            applyOrientation()
        }
        check("Garder l'écran allumé", PREF_KEEP_SCREEN_ON, true) { applyKeepScreenOn() }
        slider("Taille waveforms (part de l'écran)", PREF_WAVE_SHARE, waveShare, 0.15f, 0.85f, scopedKey = true) {
            applyWaveShare(findViewById(R.id.waves), it)
            layoutPanels()
        }
        slider("Zoom waveform (secondes visibles)", PREF_ZOOM, 8f, 2f, 32f) { seconds ->
            waves.forEach { it.visibleSeconds = seconds.toDouble() }
            waves.forEach { it.invalidate() }
        }
        val panelLabels = arrayOf("Deck A", "Mixer", "Deck B")
        PANEL_PREFS.forEachIndexed { i, key ->
            check(panelLabels[i], scoped(key), i != 1 || !upright) { show ->
                foldable[i].visibility = if (show) View.VISIBLE else View.GONE
                layoutPanels()
            }
        }

        section("Audio")
        content.addView(TextView(this).apply {
            text = "Ouvrir les réglages audio, sortie, niveaux et headphones"
            setTextColor(getColor(R.color.text_dim))
            textSize = 13f
        })
        content.addView(TextView(this).apply {
            text = "AUDIO…"
            setTextColor(getColor(R.color.text))
            setPadding(0, pad / 2, 0, pad / 2)
            setOnClickListener { showAudioSettings() }
        })
        check("Headphones over 2 channels", PREF_SPLIT, false) { checked ->
            engine.splitCue = checked
        }

        section("Enregistrement")
        content.addView(TextView(this).apply {
            text = "Dossier REC: ${recordings}"
            setTextColor(getColor(R.color.text_dim))
            textSize = 13f
        })
        content.addView(TextView(this).apply {
            text = "Choisir le dossier d'enregistrement…"
            setTextColor(getColor(R.color.text))
            setPadding(0, pad / 2, 0, pad / 2)
            setOnClickListener { chooseRecordingFolder() }
        })
        content.addView(TextView(this).apply {
            text = "Réinitialiser au dossier par défaut"
            setTextColor(getColor(R.color.text_dim))
            setPadding(0, pad / 2, 0, pad / 2)
            setOnClickListener {
                preferences.edit().remove(PREF_RECORDING_TREE).apply()
                toast("Dossier REC réinitialisé")
            }
        })

        val scroll = ScrollView(this).apply { addView(content) }
        AlertDialog.Builder(this)
            .setTitle("Paramètres")
            .setView(scroll)
            .setPositiveButton("OK", null)
            .show()
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
                textSize = 13f
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
            text = "Headphones over 2 channels: master mono left, headphones mono right. " +
                "With the FLX4: its rear switch on MONO (Android) first, when its headphones stay silent " +
                "(the Pixel 9 family and other phones only send it 2 channels). Never with the switch on STEREO: " +
                "the cue would play on the right speaker. Also for a splitter cable on the phone's jack."
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
        const val REQ_FILES = 42
        const val REQ_RECORDING_TREE = 43
        const val PREFS = "flx4-preferences"
        const val PREF_FOLDER = "folder" // up to 0.4: the one music folder
        const val PREF_FOLDERS = "folders"
        const val PREF_FILES = "files"
        const val PREF_HIDDEN = "hidden" // tracks removed one by one from an added folder
        const val PREF_PLAYLISTS = "playlists"
        const val PREF_WAVE_SHARE = "wave_share"
        const val PREF_SPLIT = "phones_over_2" // not "split_cue": up to 0.6.0 that box did nothing on the FLX4 and may be left ticked
        const val PREF_ZOOM = "zoom"
        const val PREF_DISABLE_UPRIGHT = "disable_upright"
        const val PREF_KEEP_SCREEN_ON = "keep_screen_on"
        const val PREF_RECORDING_TREE = "recording_tree"
        val PANEL_PREFS = arrayOf("panel_deck_a", "panel_mixer", "panel_deck_b")

        /** Two LOAD presses closer than this are a double press (instant doubles). */
        const val DOUBLE_PRESS_MS = 400L
        const val REALIGN_MS = 700L

        /** Tempo fader distance (1 % of its travel) at which it takes over. */
        const val TAKEOVER = 0.02
        const val TICK_MS = 50L

        /** The background service stops after this long without playback. */
        const val SERVICE_IDLE_MS = 10 * 60_000L
        val DECK_NAMES = arrayOf("A", "B")
        val FX_BEATS = doubleArrayOf(0.125, 0.25, 0.5, 0.75, 1.0, 2.0, 4.0, 8.0)
        val FX_BEAT_LABELS = arrayOf("1/8", "1/4", "1/2", "3/4", "1", "2", "4", "8")
        val FX_TARGET_LABELS = arrayOf("CH A", "CH B", "MASTER")

        /** Where recordings go, under the phone's shared storage. */
        const val RECORDINGS = "Music/FLX4 Pocket"
    }
}
