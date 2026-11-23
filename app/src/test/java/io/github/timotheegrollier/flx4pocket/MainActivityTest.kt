package io.github.timotheegrollier.flx4pocket

import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.android.controller.ActivityController
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
