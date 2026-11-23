package io.github.timotheegrollier.flx4pocket

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class DeckPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    interface Callbacks {
        fun onPlay(deck: Int)
        fun onCue(deck: Int)
        fun onSync(deck: Int)
        fun onTap(deck: Int)
        fun onTempo(deck: Int, progress: Int)
        fun onVolume(deck: Int, progress: Int)
        fun onTrim(deck: Int, progress: Int)
        fun onFilter(deck: Int, progress: Int)
        fun onEq(deck: Int, band: Int, progress: Int)
        fun onLoopIn(deck: Int)
        fun onLoopOut(deck: Int)
        fun onReloop(deck: Int)
        fun onPad(deck: Int, pad: Int)
        fun onMaster(deck: Int)
    }

    var callbacks: Callbacks? = null

    private lateinit var label: TextView
    private lateinit var bpmLabel: TextView
    private lateinit var titleLabel: TextView
    private lateinit var metaLabel: TextView
    private lateinit var beatBar: BeatBarView
    private lateinit var timeLabel: TextView
    private lateinit var modeLabel: TextView
    private lateinit var pitchLabel: TextView

    private lateinit var playButton: Button
    private lateinit var cueButton: Button
    private lateinit var syncButton: Button
    private lateinit var tapButton: Button
    private lateinit var loopInButton: Button
    private lateinit var loopOutButton: Button
    private lateinit var reloopButton: Button
    private lateinit var masterButton: Button

    private lateinit var tempo: SeekBar
    private lateinit var volume: SeekBar
    private lateinit var trim: SeekBar
    private lateinit var filter: SeekBar
    private lateinit var eqHigh: SeekBar
    private lateinit var eqMid: SeekBar
    private lateinit var eqLow: SeekBar
    private val pads = ArrayList<Button>(8)
    private lateinit var seekBars: List<SeekBar>

    private var deckIndex = 0
    private var accent = Color.CYAN
    private var loading: String? = null
    private var error: String? = null
    private var lastPadMode = -1
    private var loopPad = -1

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_deck, this, true)

        label = findViewById(R.id.deck_label)
        bpmLabel = findViewById(R.id.deck_bpm)
        titleLabel = findViewById(R.id.deck_title)
        metaLabel = findViewById(R.id.deck_meta)
        beatBar = findViewById(R.id.deck_beat)
        timeLabel = findViewById(R.id.deck_time)
        modeLabel = findViewById(R.id.deck_mode)
        pitchLabel = findViewById(R.id.deck_pitch)
        playButton = findViewById(R.id.deck_play)
        cueButton = findViewById(R.id.deck_cue)
        syncButton = findViewById(R.id.deck_sync)
        tapButton = findViewById(R.id.deck_tap)
        loopInButton = findViewById(R.id.deck_loop_in)
        loopOutButton = findViewById(R.id.deck_loop_out)
        reloopButton = findViewById(R.id.deck_reloop)
        masterButton = findViewById(R.id.deck_master)
        tempo = findViewById(R.id.deck_tempo)
        volume = findViewById(R.id.deck_volume)
        trim = findViewById(R.id.deck_trim)
        filter = findViewById(R.id.deck_filter)
        eqHigh = findViewById(R.id.deck_eq_hi)
        eqMid = findViewById(R.id.deck_eq_mid)
        eqLow = findViewById(R.id.deck_eq_low)
        seekBars = listOf(tempo, volume, trim, filter, eqHigh, eqMid, eqLow)

        pads.clear()
        pads.add(findViewById(R.id.deck_pad_0))
        pads.add(findViewById(R.id.deck_pad_1))
        pads.add(findViewById(R.id.deck_pad_2))
        pads.add(findViewById(R.id.deck_pad_3))
        pads.add(findViewById(R.id.deck_pad_4))
        pads.add(findViewById(R.id.deck_pad_5))
        pads.add(findViewById(R.id.deck_pad_6))
        pads.add(findViewById(R.id.deck_pad_7))

        playButton.setOnClickListener { callbacks?.onPlay(deckIndex) }
        cueButton.setOnClickListener { callbacks?.onCue(deckIndex) }
        syncButton.setOnClickListener { callbacks?.onSync(deckIndex) }
        tapButton.setOnClickListener { callbacks?.onTap(deckIndex) }
        loopInButton.setOnClickListener { callbacks?.onLoopIn(deckIndex) }
        loopOutButton.setOnClickListener { callbacks?.onLoopOut(deckIndex) }
        reloopButton.setOnClickListener { callbacks?.onReloop(deckIndex) }
        masterButton.setOnClickListener { callbacks?.onMaster(deckIndex) }
        pads.forEachIndexed { index, button ->
            button.setOnClickListener { callbacks?.onPad(deckIndex, index) }
        }

        addSeekBar(tempo) { callbacks?.onTempo(deckIndex, it) }
        addSeekBar(volume) { callbacks?.onVolume(deckIndex, it) }
        addSeekBar(trim) { callbacks?.onTrim(deckIndex, it) }
        addSeekBar(filter) { callbacks?.onFilter(deckIndex, it) }
        addSeekBar(eqHigh) { callbacks?.onEq(deckIndex, 0, it) }
        addSeekBar(eqMid) { callbacks?.onEq(deckIndex, 1, it) }
        addSeekBar(eqLow) { callbacks?.onEq(deckIndex, 2, it) }
    }

    fun bind(index: Int, accentColor: Int) {
        deckIndex = index
        accent = accentColor
        label.text = if (index == 0) "DECK A" else "DECK B"
        label.setTextColor(accentColor)
        label.contentDescription = label.text
        seekBars.forEach {
            it.progressTintList = ColorStateList.valueOf(accentColor)
            it.thumbTintList = ColorStateList.valueOf(accentColor)
        }
        pads.forEach { it.setTextColor(context.getColor(R.color.text)) }
    }

    fun setLoading(text: String?) {
        loading = text
        error = null
    }

    fun setError(message: String?) {
        error = message
        loading = null
    }

    fun setLoopPad(index: Int) {
        loopPad = index
    }

    fun render(deck: Deck, masterIndex: Int) {
        val hasTrack = deck.hasTrack()
        titleLabel.text = when {
            error != null -> error
            loading != null -> loading
            hasTrack -> deck.title.ifBlank { "Untitled track" }
            else -> "Load a track"
        }
        titleLabel.setTextColor(
            when {
                error != null -> context.getColor(R.color.danger)
                hasTrack -> context.getColor(R.color.text)
                else -> context.getColor(R.color.text_dim)
            }
        )
        metaLabel.text = when {
            error != null -> context.getString(R.string.status_controller, "Load a track first")
            loading != null -> context.getString(R.string.status_output, "Decoding audio…")
            hasTrack -> deck.artist.ifBlank { context.getString(R.string.status_output, "Analyzing…") }
            else -> context.getString(R.string.status_output, "FLX4 LOAD 1 / 2 or open LIBRARY")
        }

        val bpm = deck.effectiveBpm()
        bpmLabel.text = if (bpm > 0.0) {
            val prefix = if (deck.gridConfidence > 0.0 && deck.gridConfidence < 1.3) "~" else ""
            String.format(Locale.US, "%s%.1f BPM", prefix, bpm)
        } else {
            "-- BPM"
        }

        timeLabel.text = String.format(
            Locale.US,
            "%s / %s",
            formatTime(deck.positionSec),
            formatTime(deck.durationSec)
        )
        pitchLabel.text = String.format(Locale.US, "%+.1f%%", (deck.tempoRate - 1.0) * 100.0)

        beatBar.setState(
            deck.phase(),
            deck.grid.beatIndex(deck.positionSec),
            deck.playing,
            accent
        )

        playButton.text = if (deck.playing) "PAUSE" else "PLAY"
        styleButton(playButton, deck.playing, true)
        styleButton(cueButton, !deck.playing && hasTrack, false)
        styleButton(syncButton, deck.syncEnabled, deck.syncEnabled)
        styleButton(masterButton, masterIndex == deckIndex, true)
        styleButton(loopInButton, deck.loopInSec >= 0.0, deck.loopInSec >= 0.0)
        styleButton(loopOutButton, deck.loopOutSec > deck.loopInSec, deck.loopOutSec > deck.loopInSec)
        styleButton(reloopButton, deck.loopActive, deck.loopActive)

        if (lastPadMode != deck.padMode) {
            lastPadMode = deck.padMode
            updatePadLabels(deck.padMode)
        }
        modeLabel.text = when (deck.padMode) {
            Deck.PAD_BEAT_LOOP -> "BEAT LOOP"
            Deck.PAD_BEAT_JUMP -> "BEAT JUMP"
            else -> "HOT CUE"
        }

        pads.forEachIndexed { index, pad ->
            val active = when (deck.padMode) {
                Deck.PAD_HOT_CUE -> deck.hotCues.getOrNull(index) != null
                Deck.PAD_BEAT_LOOP -> loopPad == index
                else -> false
            }
            stylePad(pad, active)
        }

        if (!tempo.isPressed) {
            val normalized = if (abs(deck.tempoRange) < 1e-6) 0.0
            else (deck.tempoRate - 1.0) / deck.tempoRange
            tempo.progress = ((normalized * 0.5 + 0.5) * 1000.0).roundToInt().coerceIn(0, 1000)
        }
        if (!volume.isPressed) volume.progress = (deck.volume * 1000f).roundToInt()
        if (!trim.isPressed) trim.progress = (deck.trim * 1000f).roundToInt()
        if (!filter.isPressed) filter.progress = (deck.filter * 1000f).roundToInt()
        if (!eqHigh.isPressed) eqHigh.progress = (deck.eqHigh * 1000f).roundToInt()
        if (!eqMid.isPressed) eqMid.progress = (deck.eqMid * 1000f).roundToInt()
        if (!eqLow.isPressed) eqLow.progress = (deck.eqLow * 1000f).roundToInt()
    }

    fun publishSpectrum(spectrum: FloatArray?) {
        beatBar.publishSpectrum(spectrum)
    }

    private fun updatePadLabels(mode: Int) {
        val labels = when (mode) {
            Deck.PAD_BEAT_LOOP -> listOf("1/4", "1/2", "1", "2", "4", "8", "16", "32")
            Deck.PAD_BEAT_JUMP -> listOf("-1", "+1", "-2", "+2", "-4", "+4", "-8", "+8")
            else -> listOf("1", "2", "3", "4", "5", "6", "7", "8")
        }
        pads.forEachIndexed { index, button -> button.text = labels[index] }
    }

    private fun styleButton(button: Button, active: Boolean, emphasize: Boolean) {
        button.backgroundTintList = ColorStateList.valueOf(
            if (active) accent else context.getColor(R.color.panel_alt)
        )
        button.setTextColor(if (active || emphasize) context.getColor(R.color.text) else context.getColor(R.color.text_dim))
    }

    private fun stylePad(pad: Button, active: Boolean) {
        pad.backgroundTintList = ColorStateList.valueOf(
            if (active) accent else context.getColor(R.color.panel_alt)
        )
        pad.setTextColor(if (active) context.getColor(R.color.text) else context.getColor(R.color.text_dim))
    }

    private fun addSeekBar(bar: SeekBar, onChange: (Int) -> Unit) {
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) onChange(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun formatTime(seconds: Double): String {
        if (!seconds.isFinite() || seconds < 0.0) return "00:00"
        val total = seconds.toInt()
        val minutes = total / 60
        val secs = total % 60
        return String.format(Locale.US, "%02d:%02d", minutes, secs)
    }
}
