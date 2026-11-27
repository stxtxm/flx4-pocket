package io.github.timotheegrollier.flx4pocket

import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.android.controller.ActivityController
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowChoreographer
import java.io.File

/**
 * Smoke test of the real screen on the JVM: the layouts inflate, the activity
 * survives its lifecycle, and the touch controls reach the engine.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w915dp-h412dp-land-420dpi")
class MainActivityTest {

    private var controller: ActivityController<MainActivity>? = null

    @Before
    fun pauseFrames() {
        // The screen redraws itself on every display frame, forever; the tests
        // step it by hand instead so the main looper can go idle.
        ShadowChoreographer.setPaused(true)
    }

    @After
    fun destroy() {
        controller?.pause()?.stop()?.destroy()
    }

    private fun launch() = Robolectric.buildActivity(MainActivity::class.java).setup().also { controller = it }

    /** Texts and button states are refreshed every sixth display frame. */
    private fun refresh(activity: MainActivity) = repeat(6) { activity.doFrame(0) }

    private fun tap(view: View, action: Int) {
        val event = MotionEvent.obtain(0, 0, action, 10f, 10f, 0)
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    /** A deck with a synthetic four-on-the-floor waveform, as after a real analysis. */
    private fun loadDemoTrack(deck: Deck, title: String, artist: String, bpm: Double, at: Double) {
        val rate = 44100
        val seconds = 60
        deck.load(object : PcmSource {
            override val sampleRate = rate
            override val durationSec = seconds.toDouble()
            override fun sampleFrame(frame: Double, out: FloatArray) = true
            override fun hasFrame(frame: Double) = true
            override fun seek(seconds: Double) {}
            override fun close() {}
        }, title, artist, 0.0)
        val builder = WaveformBuilder(rate, seconds.toDouble())
        val beat = rate * 60.0 / bpm
        val block = FloatArray(rate * 2)
        for (second in 0 until seconds) {
            for (f in 0 until rate) {
                val n = second * rate + f
                val inBeat = (n % beat) / beat
                val kick = Math.sin(2 * Math.PI * 55.0 * n / rate) * Math.exp(-inBeat * 9.0)
                val hat = (Math.random() - 0.5) * 0.5 * Math.exp(-((inBeat + 0.5) % 1.0) * 14.0)
                val pad = 0.15 * Math.sin(2 * Math.PI * 880.0 * n / rate) * (if (second % 16 < 8) 1.0 else 0.3)
                val v = ((kick * 0.8 + hat + pad) * (if (second in 20..23) 0.25 else 1.0)).toFloat()
                block[f * 2] = v
                block[f * 2 + 1] = v
            }
            builder.add(block, rate)
        }
        builder.waveform.complete = true
        deck.waveform = builder.waveform
        deck.setGrid(bpm, 0.0, 2.0)
        deck.seekTo(at)
    }

    @Test
    fun `activity starts, renders and shuts down`() {
        val controller = launch()
        val activity = controller.get()
        refresh(activity)
        assertEquals("v${BuildConfig.VERSION_NAME}", activity.findViewById<TextView>(R.id.btn_update).text.toString())
        assertEquals(
            "first run opens the library to pick a folder",
            View.VISIBLE, activity.findViewById<View>(R.id.library_panel).visibility
        )

        // Render the mixing screen to a PNG so a human can look at the layout.
        activity.findViewById<View>(R.id.library_panel).visibility = View.GONE
        loadDemoTrack(activity.engine.decks[0], "Night Drive", "Demo Artist", bpm = 124.0, at = 31.3)
        loadDemoTrack(activity.engine.decks[1], "Second Track", "", bpm = 126.0, at = 12.0)
        activity.engine.decks[0].padDown(Deck.PAD_HOT_CUE, 0)
        activity.engine.decks[0].seekTo(29.5)
        activity.engine.decks[0].cueDown() // a cue point here, saved as a memory cue
        activity.engine.decks[0].cueUp()
        activity.engine.decks[0].saveMemoryCue()
        activity.engine.decks[0].seekTo(33.0)
        activity.engine.decks[0].padDown(Deck.PAD_HOT_CUE, 3)
        activity.engine.decks[0].toggleBeatLoop(4.0)
        activity.engine.decks[0].seekTo(32.2)
        refresh(activity)
        val root = screenshot(activity, "main.png")
        assertTrue(root.width > root.height)

        for (id in listOf(R.id.toggle_deck_a, R.id.toggle_mixer, R.id.toggle_deck_b)) {
            activity.findViewById<View>(id).performClick()
        }
        screenshot(activity, "waveforms.png")

        fun track(name: String, folder: String) = Track(Uri.parse("content://demo/$name"), name, 0L, folder)
        val analysed = AnalysisCache(activity)
        analysed.put(track("Night Drive.mp3", "Music/House"), BpmAnalyzer.Result(124.0, 0.0, 2.0, 372.0))
        analysed.put(track("Second Track.flac", "Music/House"), BpmAnalyzer.Result(126.0, 0.0, 2.0, 305.0))
        activity.setLibrary(
            listOf(
                track("Night Drive.mp3", "Music/House"), track("Second Track.flac", "Music/House"),
                track("Deep Water.mp3", "Music/House/Deep"), track("Warehouse.mp3", "Music/Techno"),
                track("Acid Rain.wav", "Music/Techno"), track("Vocal Hook.mp3", Library.ADDED_FILES)
            )
        )
        activity.addToPlaylist("Warm-up", track("Deep Water.mp3", "Music/House/Deep"))
        activity.onBrowse(1, shift = false) // the first click opens the library
        activity.onButton(-1, Flx4Button.BROWSE_PRESS, true)
        activity.onBrowse(3, shift = false)
        screenshot(activity, "library.png")
        val row = activity.findViewById<android.widget.ListView>(R.id.library_list).getChildAt(0)
        assertEquals("the tempo has its own column in the track list", "124.0", row.findViewById<TextView>(R.id.row_bpm).text.toString())
    }

    /** Waits for the mixer thread, which runs on its own in these tests. */
    private fun eventually(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3000
        while (!condition()) {
            assertTrue(what, System.currentTimeMillis() < deadline)
            Thread.sleep(5)
        }
    }

    @Test
    fun `the deck on air is the sync master, not one that is stopped`() {
        val activity = launch().get()
        val engine = activity.engine
        loadDemoTrack(engine.decks[0], "Next", "", bpm = 124.0, at = 0.0)
        loadDemoTrack(engine.decks[1], "On Air", "", bpm = 126.0, at = 0.0)
        assertEquals(0, engine.masterDeckIndex)
        engine.decks[1].togglePlay() // deck B is on air; deck A, the master so far, is stopped
        eventually("the playing deck takes the master role") { engine.masterDeckIndex == 1 }
    }

    @Test
    fun `SYNC on either deck makes it follow the other, playing or not`() {
        val activity = launch().get()
        val engine = activity.engine
        val onAir = engine.decks[1]
        val next = engine.decks[0]
        loadDemoTrack(onAir, "On Air", "", bpm = 126.0, at = 0.0)
        loadDemoTrack(next, "Next", "", bpm = 124.0, at = 0.0)
        onAir.togglePlay()
        eventually("the playing deck takes the master role") { engine.masterDeckIndex == 1 }

        activity.onSync(1) // SYNC on the deck on air: it follows the other deck, as on rekordbox
        assertTrue("the deck on air takes the sync", onAir.syncEnabled)
        assertEquals("and the stopped deck becomes the master", 0, engine.masterDeckIndex)
        assertEquals("on the master's tempo", 124.0, onAir.effectiveBpm(), 0.001)

        activity.onSync(0) // SYNC on the master swaps the roles
        assertTrue("the other deck follows now", next.syncEnabled)
        assertFalse("the deck that was following handed the master role over", onAir.syncEnabled)
        assertEquals("and owns the master role", 1, engine.masterDeckIndex)
        assertEquals("at the tempo the pair was playing", 124.0, next.effectiveBpm(), 0.001)
    }

    @Test
    fun `a channel meter reads before the fader and the crossfader, as on the FLX4`() {
        val activity = launch().get()
        val engine = activity.engine
        engine.decks[1].load(object : PcmSource { // a tone at half of full scale, -6 dBFS
            override val sampleRate = 44100
            override val durationSec = 3600.0 // the mixer runs faster than real time in these tests
            override fun sampleFrame(frame: Double, out: FloatArray): Boolean {
                val v = (0.5 * Math.sin(frame * 0.05)).toFloat()
                out[0] = v
                out[1] = v
                return true
            }
            override fun hasFrame(frame: Double) = true
            override fun seek(seconds: Double) {}
            override fun close() {}
        }, "Tone", "", 0.0)
        engine.decks[1].volume = 0f // its fader down
        engine.crossfader = 0f // and the crossfader on deck A: nothing of deck B reaches the master
        Thread.sleep(100) // the faders glide to their new place over one block: let them get there first
        engine.decks[1].togglePlay()
        eventually("deck B's meter still shows its level") { engine.level(1) > 0.45f }
        assertTrue("and nothing of it in the master: ${engine.meterL}", engine.meterL < 0.01f)
    }

    @Test
    fun `REC records until it is pressed again`() {
        val activity = launch().get()
        val rec = activity.findViewById<TextView>(R.id.btn_rec)
        refresh(activity)
        assertEquals("REC", rec.text.toString())
        rec.performClick()
        assertTrue(activity.engine.recorder != null)
        refresh(activity)
        if (activity.engine.recorder != null) { // these tests have no media store: the recording may already have ended, with a message
            assertTrue(rec.text.toString(), rec.text.startsWith("●"))
            assertEquals(activity.getColor(R.color.danger), rec.backgroundTintList!!.defaultColor)
            rec.performClick()
        }
        assertEquals(null, activity.engine.recorder)
        refresh(activity)
        assertEquals("REC", rec.text.toString())
        val said = org.robolectric.shadows.ShadowToast.getTextOfLatestToast()
        assertTrue(said, said.contains("Music/FLX4 Pocket") || said.startsWith("Cannot record"))
    }

    /** Lays the screen out at the configured size and writes it to build/screenshots. */
    private fun screenshot(activity: MainActivity, name: String): View {
        val root = activity.window.decorView
        val metrics = activity.resources.displayMetrics
        root.measure(
            View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        File("build/screenshots").mkdirs()
        File("build/screenshots/$name").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        return root
    }

    @Test
    fun `held upright the screen restacks, with the same engine`() {
        val controller = launch()
        val activity = controller.get()
        activity.onBackPressed() // closes the first-run library
        loadDemoTrack(activity.engine.decks[0], "Night Drive", "Demo Artist", bpm = 124.0, at = 31.3)
        loadDemoTrack(activity.engine.decks[1], "Second Track", "", bpm = 126.0, at = 12.0)
        val engine = activity.engine
        val deckA = activity.findViewById<View>(R.id.deck_a)
        val deckB = activity.findViewById<View>(R.id.deck_b)
        val mixer = activity.findViewById<View>(R.id.mixer)
        val status = activity.findViewById<View>(R.id.status)

        RuntimeEnvironment.setQualifiers("w412dp-h915dp-port-420dpi")
        controller.configurationChange()
        assertSame("the activity owns the engine: a rotation must not recreate it", activity, controller.get())
        assertSame(engine, activity.engine)
        assertEquals("Night Drive", engine.decks[0].title)
        refresh(activity)
        val update = activity.findViewById<TextView>(R.id.btn_update)
        update.text = "UPDATE 0.6.1" // the longest label the version button takes
        val root = screenshot(activity, "upright.png")
        assertTrue(root.height > root.width)
        val at = IntArray(2)
        update.getLocationInWindow(at)
        assertTrue("the version button fits: ${at[0] + update.width} > ${root.width}", at[0] + update.width <= root.width)
        assertEquals("three panels are too many for one column: the mixer starts folded", View.GONE, mixer.visibility)
        assertTrue("the decks stack, each the full width", deckB.top >= deckA.bottom && deckA.width > root.width * 0.9)
        assertTrue("the status line has a row of its own", status.width > root.width / 2)

        activity.findViewById<View>(R.id.toggle_mixer).performClick()
        activity.findViewById<View>(R.id.toggle_deck_b).performClick()
        screenshot(activity, "upright-mixer.png")
        fun track(name: String, folder: String) = Track(Uri.parse("content://demo/$name"), name, 0L, folder)
        activity.setLibrary(listOf(track("Night Drive.mp3", "Music/House"), track("Warehouse.mp3", "Music/Techno")))
        activity.onBrowse(1, shift = false)
        val folders = activity.findViewById<View>(R.id.library_folders)
        val tracks = activity.findViewById<View>(R.id.library_list)
        screenshot(activity, "upright-library.png")
        assertTrue("folders above the tracks, both the full width", folders.width == tracks.width && folders.height < tracks.height)
        activity.onBackPressed()

        RuntimeEnvironment.setQualifiers("w915dp-h412dp-land-420dpi")
        controller.configurationChange()
        screenshot(activity, "side.png")
        assertEquals("on its side again, with the panels that were open there", View.VISIBLE, deckB.visibility)
        assertTrue("side by side: ${deckA.right} ${mixer.left} ${mixer.right} ${deckB.left}", deckB.left >= mixer.right && mixer.left >= deckA.right)
    }

    @Test
    fun `the screen keeps clear of the camera hole and of the rounded corners`() {
        val controller = launch()
        val root = controller.get().findViewById<View>(R.id.root)
        fun display(cutout: android.graphics.Insets) = android.view.WindowInsets.Builder()
            .setInsets(android.view.WindowInsets.Type.displayCutout(), cutout)
            .apply { for (corner in 0..3) setRoundedCorner(corner, android.view.RoundedCorner(corner, 100, 100, 100)) }
            .build()
        fun padding() = listOf(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)

        root.dispatchApplyWindowInsets(display(android.graphics.Insets.of(130, 0, 0, 0)))
        assertEquals("on its side: the hole on the left, 60 % of a corner on the right, the full height", listOf(130, 0, 60, 0), padding())

        RuntimeEnvironment.setQualifiers("w412dp-h915dp-port-420dpi")
        controller.configurationChange()
        root.dispatchApplyWindowInsets(display(android.graphics.Insets.of(0, 130, 0, 0)))
        assertEquals("upright: the hole on top, the corners below, the full width", listOf(0, 130, 0, 60), padding())
    }

    @Test
    fun `panels fold away and leave the waveforms`() {
        val activity = launch().get()
        activity.onBackPressed() // closes the first-run library
        val area = activity.findViewById<View>(R.id.panels)
        val toggles = listOf(R.id.toggle_deck_a, R.id.toggle_mixer, R.id.toggle_deck_b)
        val panels = listOf(R.id.deck_a, R.id.mixer, R.id.deck_b)
        toggles.forEach { activity.findViewById<View>(it).performClick() }
        panels.forEach { assertEquals(View.GONE, activity.findViewById<View>(it).visibility) }
        assertEquals("only the waveforms are left", View.GONE, area.visibility)
        assertFalse(activity.findViewById<View>(R.id.toggle_mixer).isSelected)

        activity.onBrowse(1, shift = false) // the library still opens, where the panels were
        assertEquals(View.VISIBLE, area.visibility)
        activity.onBackPressed()
        assertEquals(View.GONE, area.visibility)

        activity.findViewById<View>(R.id.toggle_mixer).performClick()
        assertEquals(View.VISIBLE, area.visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.mixer).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.deck_a).visibility)

        controller?.pause()?.stop()?.destroy()
        val again = launch().get() // the choice is remembered
        assertEquals(View.GONE, again.findViewById<View>(R.id.deck_b).visibility)
        assertEquals(View.VISIBLE, again.findViewById<View>(R.id.mixer).visibility)
    }

    @Test
    fun `pad mode tabs relabel the pads`() {
        val activity = launch().get()
        val deck = activity.findViewById<View>(R.id.deck_a)
        refresh(activity)
        assertEquals("A", deck.findViewById<TextView>(R.id.deck_pad_0).text.toString())
        deck.findViewById<View>(R.id.deck_tab_1).performClick()
        refresh(activity)
        assertEquals("1/4", deck.findViewById<TextView>(R.id.deck_pad_0).text.toString())
        deck.findViewById<View>(R.id.deck_tab_3).performClick()
        refresh(activity)
        assertEquals("ECHO\n1/4", deck.findViewById<TextView>(R.id.deck_pad_0).text.toString())
    }

    @Test
    fun `beat fx controls drive the engine`() {
        val activity = launch().get()
        val fxOn = activity.findViewById<TextView>(R.id.fx_on)
        activity.findViewById<View>(R.id.fx_select).performClick()
        activity.findViewById<View>(R.id.fx_beat_up).performClick()
        activity.findViewById<View>(R.id.fx_target).performClick()
        fxOn.performClick()
        refresh(activity)
        assertEquals("LC ECHO", activity.findViewById<TextView>(R.id.fx_select).text.toString())
        assertEquals("3/4", activity.findViewById<TextView>(R.id.fx_beats).text.toString())
        assertEquals("CH A", activity.findViewById<TextView>(R.id.fx_target).text.toString())
        assertEquals(activity.getColor(R.color.accent), fxOn.backgroundTintList!!.defaultColor)
    }

    @Test
    fun `controller moves from the MIDI thread reach the decks at once`() {
        val activity = launch().get()
        var failure: Throwable? = null
        val midi = Thread {
          try {
            activity.onKnob(0, Flx4Knob.EQ_LOW, 0f)
            activity.onKnob(-1, Flx4Knob.CROSSFADER, 1f)
            activity.onJog(0, Flx4Jog.BEND, 3)
            activity.onButton(0, Flx4Button.CUE, true)
            activity.onButton(0, Flx4Button.CUE, false)
            activity.onPad(0, Deck.PAD_HOT_CUE, 0, shift = false, down = true)
            activity.onButton(0, Flx4Button.SYNC, true) // waits for the main thread
            activity.onButton(0, Flx4Button.PLAY, true) // so this one queues behind it
          } catch (e: Throwable) {
            failure = e
          }
        }
        midi.start()
        midi.join()
        assertEquals(null, failure)
        assertEquals(0f, activity.engine.decks[0].eqLow)
        assertEquals(1f, activity.engine.crossfader)
        shadowOf(Looper.getMainLooper()).idle() // the LEDs and the screen answer on the main thread
    }

    @Test
    fun `controller events are applied without a controller attached`() {
        val activity = launch().get()
        activity.onKnob(-1, Flx4Knob.CROSSFADER, 1f)
        activity.onKnob(0, Flx4Knob.EQ_LOW, 0f)
        activity.onPadMode(1, Deck.PAD_BEAT_LOOP)
        activity.onButton(-1, Flx4Button.SMART_CFX, true)
        activity.onButton(0, Flx4Button.FX_CHANNEL, true)
        activity.onButton(1, Flx4Button.FX_CHANNEL, false)
        activity.onButton(0, Flx4Button.PLAY, true) // no track: must not crash
        activity.onJog(0, Flx4Jog.SCRATCH, 5)
        activity.onPad(0, Deck.PAD_FX1, 4, shift = false, down = true)
        refresh(activity)

        assertEquals(1f, activity.findViewById<FaderView>(R.id.mixer_crossfader).value, 1e-3f)
        assertEquals("CH A", activity.findViewById<TextView>(R.id.fx_target).text.toString())
        assertEquals(
            "32",
            activity.findViewById<View>(R.id.deck_b).findViewById<TextView>(R.id.deck_pad_7).text.toString()
        )
        assertFalse(activity.isFinishing)
    }

    @Test
    fun `tapping an empty deck opens the library for that deck`() {
        val activity = launch().get()
        val library = activity.findViewById<View>(R.id.library_panel)
        val target = activity.findViewById<TextView>(R.id.library_target)
        activity.onBackPressed() // closes the first-run library
        assertEquals(View.GONE, library.visibility)

        val wave = activity.findViewById<View>(R.id.wave_b).findViewById<View>(R.id.wave)
        tap(wave, MotionEvent.ACTION_DOWN)
        assertTrue("an empty deck takes a touch as a tap, not a scratch", wave.isPressed)
        tap(wave, MotionEvent.ACTION_UP)
        wave.performClick() // the click a real tap posts needs a window, which these tests never attach
        assertEquals(View.VISIBLE, library.visibility)
        assertEquals("LOAD TO B", target.text.toString())

        activity.onBackPressed()
        activity.onButton(0, Flx4Button.PLAY, true) // PLAY on an empty deck does the same
        assertEquals("LOAD TO A", target.text.toString())
        activity.onBackPressed()
        assertEquals(View.GONE, target.visibility)

        activity.onBrowse(1, shift = false) // the first browse click only opens the library
        assertEquals(View.VISIBLE, library.visibility)
        assertEquals(View.GONE, target.visibility)

        // While browsing, a finger resting on a playing deck's waveform must not hold the track.
        val deckA = activity.engine.decks[0]
        loadDemoTrack(deckA, "Playing", "", bpm = 124.0, at = 10.0)
        val waveA = activity.findViewById<View>(R.id.wave_a).findViewById<View>(R.id.wave)
        tap(waveA, MotionEvent.ACTION_DOWN)
        assertFalse(deckA.scratching)
        tap(waveA, MotionEvent.ACTION_UP)
        activity.onBackPressed()
        tap(waveA, MotionEvent.ACTION_DOWN)
        assertTrue(deckA.scratching)
        tap(waveA, MotionEvent.ACTION_UP)
        assertFalse(deckA.scratching)
    }

    @Test
    fun `transport buttons tolerate an empty deck`() {
        val activity = launch().get()
        val deck = activity.findViewById<View>(R.id.deck_a)
        for (id in listOf(R.id.deck_cue, R.id.deck_bend_plus, R.id.deck_pad_0)) {
            tap(deck.findViewById(id), MotionEvent.ACTION_DOWN)
            tap(deck.findViewById(id), MotionEvent.ACTION_UP)
        }
        for (id in listOf(
            R.id.deck_play, R.id.deck_sync, R.id.deck_loop_in, R.id.deck_loop_out, R.id.deck_reloop,
            R.id.deck_loop_half, R.id.deck_loop_double, R.id.deck_quantize, R.id.deck_tap, R.id.deck_range,
            R.id.deck_key
        )) {
            deck.findViewById<View>(id).performClick()
        }
        refresh(activity)
        assertEquals("±16%", deck.findViewById<TextView>(R.id.deck_range).text.toString())
        assertTrue(activity.engine.decks[0].keyLock)
    }

    @Test
    fun `the FLX4 tempo fader takes over without a jump`() {
        val activity = launch().get()
        val deck = activity.engine.decks[0]
        val pitch = activity.findViewById<View>(R.id.deck_a).findViewById<TextView>(R.id.deck_pitch)
        activity.onKnob(0, Flx4Knob.TEMPO, 0.2f) // the hardware says +6 %, the deck plays at 0 %
        refresh(activity)
        assertEquals(1.0, deck.tempoRate, 1e-9)
        assertEquals("move the fader up to take over", "▲+0.0%", pitch.text.toString())

        activity.onKnob(0, Flx4Knob.TEMPO, 0.5f) // reaches 0 %: from here it drives the tempo
        activity.onKnob(0, Flx4Knob.TEMPO, 0.4f)
        refresh(activity)
        assertEquals(1.02, deck.tempoRate, 1e-3)
        assertEquals("+2.0%", pitch.text.toString())
    }

    @Test
    fun `the controller browses folders then tracks, as in rekordbox`() {
        val activity = launch().get()
        fun track(name: String, folder: String) = Track(Uri.parse("content://test/$name"), name, 0L, folder)
        activity.setLibrary(
            listOf(
                track("a.mp3", "Music/House"), track("b.mp3", "Music/Techno"),
                track("c.mp3", "Music/House"), track("d.mp3", Library.ADDED_FILES)
            )
        )
        val tracks = activity.findViewById<android.widget.ListView>(R.id.library_list)
        assertEquals(4, tracks.adapter.count)

        activity.onButton(-1, Flx4Button.BROWSE_PRESS, true) // to the folders
        activity.onBrowse(3, shift = false) // All, Added tracks, Music, Music/House
        assertEquals("only the House tracks", 2, tracks.adapter.count)
        activity.onButton(-1, Flx4Button.BROWSE_PRESS, true) // back to the tracks
        activity.onBrowse(1, shift = false)
        assertEquals("c.mp3", (tracks.adapter.getItem(1) as Track).name)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.library_panel).visibility)

        activity.onButton(-1, Flx4Button.BROWSE_SHIFT_PRESS, true) // closes the library
        assertEquals(View.GONE, activity.findViewById<View>(R.id.library_panel).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.library_empty).visibility)
    }

    @Test
    fun `playlists keep their order and the BPM sort orders the rest`() {
        val activity = launch().get()
        val cache = AnalysisCache(activity)
        fun track(name: String, bpm: Double) = Track(Uri.parse("content://test/$name"), name, 0L, "Music").also {
            cache.put(it, BpmAnalyzer.Result(bpm, 0.0, 2.0, 180.0))
        }
        val slow = track("slow.mp3", 100.0)
        val fast = track("fast.mp3", 140.0)
        val mid = track("mid.mp3", 124.0)
        activity.setLibrary(listOf(fast, mid, slow))
        val list = activity.findViewById<android.widget.ListView>(R.id.library_list)
        fun names() = (0 until list.adapter.count).map { (list.adapter.getItem(it) as Track).name }

        activity.findViewById<View>(R.id.library_sort).performClick()
        assertEquals(listOf("slow.mp3", "mid.mp3", "fast.mp3"), names())
        activity.findViewById<View>(R.id.library_sort).performClick()

        activity.addToPlaylist("Warm-up", mid)
        activity.addToPlaylist("Warm-up", slow)
        activity.onButton(-1, Flx4Button.BROWSE_PRESS, true) // to the folders: All, Music, ♫ Warm-up
        activity.onBrowse(2, shift = false)
        assertEquals("in the order they were added", listOf("mid.mp3", "slow.mp3"), names())

        controller?.pause()?.stop()?.destroy()
        val again = launch().get() // playlists are kept
        again.setLibrary(listOf(fast, mid, slow))
        again.onButton(-1, Flx4Button.BROWSE_PRESS, true)
        again.onBrowse(2, shift = false)
        val list2 = again.findViewById<android.widget.ListView>(R.id.library_list)
        assertEquals(2, list2.adapter.count)
    }

    @Test
    fun `dragging the bar under the waveforms resizes them`() {
        val activity = launch().get()
        activity.onBackPressed() // closes the first-run library
        screenshot(activity, "resize.png") // lays the screen out
        val bar = activity.findViewById<View>(R.id.control_bar)
        val waves = activity.findViewById<View>(R.id.waves)
        val before = waves.height
        fun drag(action: Int, y: Float) {
            val event = MotionEvent.obtain(0, 0, action, bar.width / 2f, y, 0)
            bar.dispatchTouchEvent(event)
            event.recycle()
        }
        drag(MotionEvent.ACTION_DOWN, 10f)
        drag(MotionEvent.ACTION_MOVE, 110f)
        drag(MotionEvent.ACTION_UP, 110f)
        screenshot(activity, "resize.png")
        assertEquals(before + 100f, waves.height.toFloat(), 3f)
    }

    @Test
    fun `a playing deck refuses a new track`() {
        val activity = launch().get()
        val deck = activity.engine.decks[0]
        loadDemoTrack(deck, "On Air", "", bpm = 124.0, at = 10.0)
        deck.togglePlay()
        activity.loadFromRow(0, Track(Uri.parse("content://test/next.mp3"), "next.mp3", 0L))
        assertEquals("On Air", deck.title)
        assertTrue(deck.playing)
    }
}
