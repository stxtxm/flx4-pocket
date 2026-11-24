package io.github.timotheegrollier.flx4pocket

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.os.SystemClock
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Touch controls of one deck. The panel owns the deck area itself and is also
 * handed the deck's waveform header and mixer channel strip, so everything
 * that shows or edits one [Deck] lives in one class.
 */
@SuppressLint("ClickableViewAccessibility") // hold-to-act pads and buttons
class DeckPanel @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs) {

    /** Actions that need more than the deck itself. */
    interface Host {
        fun onPlay(deck: Int)
        fun onSync(deck: Int)
        fun onMaster(deck: Int)
        fun onTap(deck: Int)
        /** Hot cues or memory cues changed: save them with the track. */
        fun onCuesChanged(deck: Int)

        /** The empty deck was tapped. */
        fun onLoadRequest(deck: Int)
    }

    private lateinit var deck: Deck
    private lateinit var host: Host
    private var accent = 0
    private val panelAlt = context.getColor(R.color.panel_alt)
    private val textColor = context.getColor(R.color.text)
    private val textDim = context.getColor(R.color.text_dim)
    private val onAccent = context.getColor(R.color.on_accent)
    private val fxColor = context.getColor(R.color.accent)
    private val danger = context.getColor(R.color.danger)

    /** Shown instead of the title while a track loads or after a failed load. */
    var message: String? = null
    var messageIsError = false

    private val overview: OverviewView
    private val tabs: List<TextView>
    private val pads: List<TextView>
    private val loopIn: TextView
    private val loopOut: TextView
    private val reloop: TextView
    private val half: TextView
    private val double: TextView
    private val quantize: TextView
    private val cue: TextView
    private val play: TextView
    private val sync: TextView
    private val range: TextView
    private val tempo: FaderView
    private val pitch: TextView
    private val key: TextView

    // Waveform header and channel strip, attached in bind().
    private lateinit var title: TextView
    private lateinit var state: TextView
    private lateinit var bpm: TextView
    private lateinit var time: TextView
    private lateinit var trim: KnobView
    private lateinit var filter: KnobView
    private lateinit var eqHigh: KnobView
    private lateinit var eqMid: KnobView
    private lateinit var eqLow: KnobView
    private lateinit var volume: FaderView
    private lateinit var vu: VuView
    private lateinit var pfl: TextView

    init {
        orientation = HORIZONTAL
        LayoutInflater.from(context).inflate(R.layout.view_deck, this, true)
        overview = findViewById(R.id.deck_overview)
        tabs = listOf(R.id.deck_tab_0, R.id.deck_tab_1, R.id.deck_tab_2, R.id.deck_tab_3, R.id.deck_tab_4)
            .map { findViewById(it) }
        pads = listOf(
            R.id.deck_pad_0, R.id.deck_pad_1, R.id.deck_pad_2, R.id.deck_pad_3,
            R.id.deck_pad_4, R.id.deck_pad_5, R.id.deck_pad_6, R.id.deck_pad_7
        ).map { findViewById(it) }
        loopIn = findViewById(R.id.deck_loop_in)
        loopOut = findViewById(R.id.deck_loop_out)
        reloop = findViewById(R.id.deck_reloop)
        half = findViewById(R.id.deck_loop_half)
        double = findViewById(R.id.deck_loop_double)
        quantize = findViewById(R.id.deck_quantize)
        cue = findViewById(R.id.deck_cue)
        play = findViewById(R.id.deck_play)
        sync = findViewById(R.id.deck_sync)
        range = findViewById(R.id.deck_range)
        tempo = findViewById(R.id.deck_tempo)
        pitch = findViewById(R.id.deck_pitch)
        key = findViewById(R.id.deck_key)
    }

    fun bind(deck: Deck, accentColor: Int, header: View, strip: View, host: Host, onZoom: (Double) -> Unit) {
        this.deck = deck
        this.host = host
        accent = accentColor
        val index = deck.index

        val loadIfEmpty = View.OnClickListener { if (!deck.hasTrack()) host.onLoadRequest(index) }
        header.findViewById<WaveformView>(R.id.wave).let {
            it.deck = deck
            it.accent = accentColor
            it.onZoom = onZoom
            it.setOnClickListener(loadIfEmpty)
        }
        overview.setOnClickListener(loadIfEmpty)
        header.findViewById<View>(R.id.wave_chip).backgroundTintList = ColorStateList.valueOf(accentColor)
        header.findViewById<TextView>(R.id.wave_deck).text = if (index == 0) "A" else "B"
        title = header.findViewById(R.id.wave_title)
        state = header.findViewById(R.id.wave_state)
        bpm = header.findViewById(R.id.wave_bpm)
        time = header.findViewById(R.id.wave_time)
        overview.deck = deck
        overview.accent = accentColor

        trim = strip.findViewById(R.id.ch_trim)
        filter = strip.findViewById(R.id.ch_filter)
        eqHigh = strip.findViewById(R.id.ch_eq_hi)
        eqMid = strip.findViewById(R.id.ch_eq_mid)
        eqLow = strip.findViewById(R.id.ch_eq_low)
        volume = strip.findViewById(R.id.ch_volume)
        vu = strip.findViewById(R.id.ch_vu)
        pfl = strip.findViewById(R.id.ch_cue)
        listOf(trim, filter, eqHigh, eqMid, eqLow, volume, tempo).forEach { it.accent = accentColor }
        trim.onChange = { deck.trim = it }
        filter.onChange = { deck.filter = it }
        eqHigh.onChange = { deck.eqHigh = it }
        eqMid.onChange = { deck.eqMid = it }
        eqLow.onChange = { deck.eqLow = it }
        volume.defaultValue = 1f
        volume.onChange = { deck.volume = it }
        pfl.setOnClickListener { deck.cueEnabled = !deck.cueEnabled }

        // Like the hardware fader: pulling it down speeds the track up.
        tempo.centered = true
        tempo.onChange = { deck.setTempoNormalized((0.5 - it) * 2.0) }
        range.setOnClickListener { deck.cycleTempoRange() }
        key.setOnClickListener { deck.keyLock = !deck.keyLock }

        TAB_MODES.forEachIndexed { i, mode ->
            tabs[i].text = TAB_LABELS[i]
            tabs[i].setOnClickListener { deck.padMode = mode }
        }
        pads.forEachIndexed { i, pad -> pad.setOnTouchListener { view, event -> onPadTouch(view, i, event) } }

        // Timing buttons act when the finger lands, like the hardware ones.
        loopIn.onPress { deck.setLoopIn() }
        loopOut.onPress { deck.setLoopOut() }
        reloop.onPress { deck.fourBeatLoopOrExit() }
        // CUE/LOOP CALL ◁ ▷, as on the FLX4: halve / double a loop, otherwise call memory cues.
        half.onPress { deck.cueLoopCall(-1) }
        double.onPress { deck.cueLoopCall(1) }
        quantize.setOnClickListener { deck.quantize = !deck.quantize }
        findViewById<View>(R.id.deck_tap).onPress { host.onTap(index) }

        play.onPress { host.onPlay(index) }
        sync.setOnClickListener { host.onSync(index) }
        sync.setOnLongClickListener {
            host.onMaster(index)
            true
        }
        cue.setOnTouchListener { view, event ->
            hold(view, event, down = { deck.cueDown() }, up = { deck.cueUp() })
        }
        findViewById<View>(R.id.deck_bend_minus).setOnTouchListener { view, event ->
            hold(view, event, down = { deck.nudge = -BEND }, up = { deck.nudge = 0.0 })
        }
        findViewById<View>(R.id.deck_bend_plus).setOnTouchListener { view, event ->
            hold(view, event, down = { deck.nudge = BEND }, up = { deck.nudge = 0.0 })
        }
    }

    private fun hold(view: View, event: MotionEvent, down: () -> Unit, up: () -> Unit): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                view.isPressed = true
                down()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                view.isPressed = false
                up()
            }
        }
        return true
    }

    private val deleteHotCue = Runnable {
        deck.padDown(Deck.PAD_HOT_CUE, heldPad, shift = true)
        host.onCuesChanged(deck.index)
    }
    private var heldPad = -1

    private fun onPadTouch(view: View, pad: Int, event: MotionEvent): Boolean {
        val mode = deck.padMode
        return hold(
            view, event,
            down = {
                val existed = mode == Deck.PAD_HOT_CUE && deck.hotCues[pad] != null
                deck.padDown(mode, pad)
                if (mode == Deck.PAD_HOT_CUE) host.onCuesChanged(deck.index)
                if (existed) {
                    // Keep holding an existing hot cue to delete it.
                    heldPad = pad
                    postDelayed(deleteHotCue, 700)
                }
            },
            up = {
                removeCallbacks(deleteHotCue)
                deck.padUp(mode, pad)
            }
        )
    }

    /** Called on every display frame: control positions and meters. */
    fun refreshControls(level: Float) {
        trim.show(deck.trim)
        filter.show(deck.filter)
        eqHigh.show(deck.eqHigh)
        eqMid.show(deck.eqMid)
        eqLow.show(deck.eqLow)
        volume.show(deck.volume)
        tempo.show((0.5 - deck.tempoPosition / 2.0).toFloat())
        vu.setLevel(level)
    }

    /**
     * Called a few times per second: texts and button states. [takeover] is the
     * way the FLX4 tempo fader must move to take over (-1 up, +1 down), or 0.
     */
    fun refresh(isMaster: Boolean, takeover: Int = 0) {
        val hasTrack = deck.hasTrack()
        val text = message
        title.show(
            when {
                text != null -> text
                !hasTrack -> "Tap here to load a track"
                deck.artist.isBlank() -> deck.title
                else -> "${deck.title}  ·  ${deck.artist}"
            }
        )
        title.setTextColor(if (text != null && messageIsError) danger else if (hasTrack) textColor else textDim)

        val analysing = hasTrack && deck.waveform?.complete != true
        state.show(
            listOfNotNull(
                "ANALYSING".takeIf { analysing },
                "MASTER".takeIf { isMaster && hasTrack },
                "SMART CFX".takeIf { deck.smartCfx }
            ).joinToString("") { "  $it  " }
        )
        val tempoBpm = deck.effectiveBpm()
        bpm.show(
            when {
                tempoBpm <= 0.0 -> "--.-"
                deck.gridConfidence < 1.3 -> String.format(Locale.US, "~%.1f", tempoBpm)
                else -> String.format(Locale.US, "%.1f", tempoBpm)
            }
        )
        time.show(if (hasTrack) "-" + formatTime(deck.durationSec - deck.positionSec) else "--:--")
        val arrow = when (takeover) {
            -1 -> "▲"
            1 -> "▼"
            else -> ""
        }
        pitch.show(arrow + String.format(Locale.US, "%+.1f%%", (deck.tempoRate * deck.syncFactor - 1.0) * 100.0))
        pitch.setTextColor(if (takeover == 0) textDim else fxColor)
        range.show(if (deck.tempoRange >= 0.5) "WIDE" else "±${(deck.tempoRange * 100).toInt()}%")
        style(key, deck.keyLock)

        play.show(if (deck.playing) "❚❚" else "▶")
        style(play, deck.playing)
        style(cue, hasTrack && !deck.playing)
        style(sync, deck.syncEnabled)
        style(pfl, deck.cueEnabled)
        // IN or OUT blinks while the jog (or a drag on the waveform) moves its loop point.
        val blink = SystemClock.uptimeMillis() / 250 % 2 == 0L
        style(loopIn, if (deck.loopAdjust == Deck.ADJUST_IN) blink else deck.loopInSec >= 0.0)
        style(loopOut, if (deck.loopAdjust == Deck.ADJUST_OUT) blink else deck.loopActive)
        // The loop button doubles as the loop length readout, so halving and doubling are not blind.
        val loopBeats = deck.loopBeats()
        reloop.show(if (deck.loopActive) "${beatsLabel(loopBeats)}\nEXIT" else "4\nBEAT")
        val memory = !deck.loopActive && deck.memoryCues.isNotEmpty()
        half.show(if (memory) "◄ MEM" else "½")
        double.show(if (memory) "MEM ►" else "2×")
        style(reloop, deck.loopActive)
        style(quantize, deck.quantize)

        val mode = deck.padMode
        TAB_MODES.forEachIndexed { i, tabMode -> style(tabs[i], tabMode == mode) }
        val cues = deck.hotCues
        pads.forEachIndexed { i, pad ->
            when (mode) {
                Deck.PAD_HOT_CUE -> {
                    val at = cues[i]
                    val letter = WaveformView.HOT_CUE_LETTERS[i]
                    pad.show(if (at == null) "$letter" else "$letter\n${formatTime(at)}")
                    if (at == null) pad.tint(panelAlt, textDim) else pad.tint(WaveColors.HOT_CUES[i], onAccent)
                }
                Deck.PAD_BEAT_LOOP -> {
                    pad.show(beatsLabel(Deck.LOOP_BEATS[i]))
                    style(pad, loopBeats == Deck.LOOP_BEATS[i])
                }
                Deck.PAD_BEAT_JUMP -> {
                    pad.show(JUMP_LABELS[i])
                    style(pad, false)
                }
                else -> {
                    val slots = if (mode == Deck.PAD_FX1) Deck.PAD_FX1_SLOTS else Deck.PAD_FX2_SLOTS
                    pad.show(slots[i].label.replace(' ', '\n'))
                    if (deck.heldFxPad == i) pad.tint(fxColor, onAccent) else pad.tint(panelAlt, textColor)
                }
            }
        }
    }

    private fun style(button: TextView, active: Boolean) =
        if (active) button.tint(accent, onAccent) else button.tint(panelAlt, textColor)

    private fun formatTime(seconds: Double): String {
        val total = if (seconds.isFinite() && seconds > 0.0) seconds.toInt() else 0
        return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
    }

    private companion object {
        const val BEND = 0.04
        val TAB_MODES = intArrayOf(
            Deck.PAD_HOT_CUE, Deck.PAD_BEAT_LOOP, Deck.PAD_BEAT_JUMP, Deck.PAD_FX1, Deck.PAD_FX2
        )
        val TAB_LABELS = arrayOf("HOT\nCUE", "BEAT\nLOOP", "BEAT\nJUMP", "PAD\nFX 1", "PAD\nFX 2")
        val JUMP_LABELS = arrayOf("◄ 1", "1 ►", "◄ 2", "2 ►", "◄ 4", "4 ►", "◄ 8", "8 ►")
    }
}

/** A loop length the way DJ gear writes it: 0.25 is "1/4", 4.0 is "4", 3.3 stays "3.3". */
internal fun beatsLabel(beats: Double): String {
    val inverse = 1.0 / beats
    return when {
        beats >= 1.0 && abs(beats - beats.roundToInt()) < 0.01 -> "${beats.roundToInt()}"
        beats < 1.0 && abs(inverse - inverse.roundToInt()) < 0.02 * inverse -> "1/${inverse.roundToInt()}"
        else -> String.format(Locale.US, "%.1f", beats)
    }
}
