package io.github.timotheegrollier.flx4pocket

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Connects to the DDJ-FLX4 USB MIDI interface and turns its messages into
 * semantic callbacks. All callbacks are dispatched on the main thread so the
 * activity can touch views safely; the engine itself uses volatile state.
 */
class Flx4Midi(context: Context) {

    interface Listener {
        fun onMidiStatus(name: String?) {}
        fun onDeckPlay(deck: Int) {}
        fun onDeckCue(deck: Int) {}
        fun onDeckSync(deck: Int) {}
        fun onDeckSyncLong(deck: Int) {}
        fun onDeckTempo(deck: Int, value14: Int) {}
        fun onDeckVolume(deck: Int, value14: Int) {}
        fun onDeckTrim(deck: Int, value14: Int) {}
        fun onDeckEq(deck: Int, band: Int, value14: Int) {}
        fun onDeckFilter(deck: Int, value14: Int) {}
        fun onDeckCueToggle(deck: Int) {}
        fun onDeckTempoRange(deck: Int) {}
        fun onDeckQuantize(deck: Int) {}
        fun onDeckJog(deck: Int, delta: Int, shift: Boolean) {}
        fun onDeckJogTouch(deck: Int, down: Boolean) {}
        fun onDeckShift(deck: Int, down: Boolean) {}
        fun onLoopIn(deck: Int) {}
        fun onLoopOut(deck: Int) {}
        fun onReloop(deck: Int) {}
        fun onPad(deck: Int, note: Int, shift: Boolean) {}
        fun onPadMode(deck: Int, mode: Int) {}
        fun onCrossfader(value14: Int) {}
        fun onHeadphoneMix(value14: Int) {}
        fun onBrowse(delta: Int) {}
        fun onBrowsePress() {}
        fun onLoad(deck: Int) {}
    }

    private val midiManager = context.getSystemService(MidiManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val appContext = context.applicationContext
    private var listener: Listener? = null

    private var device: MidiDevice? = null
    private var midiInput: MidiInputPort? = null
    private val receivers = mutableListOf<MidiOutputPort>()
    @Volatile
    private var connectedName: String? = null

    private val ledCache = HashMap<Int, Boolean>()
    private val valueMsb = IntArray(128) { -1 }
    private val valueLsb = IntArray(128) { -1 }

    private var parserStatus = 0
    private var parserData1 = 0
    private var parserDataCount = 0
    private var inSysex = false

    private val deviceCallback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(info: MidiDeviceInfo) {
            if (connectedName == null && isFlx4(info)) open(info)
        }

        override fun onDeviceRemoved(info: MidiDeviceInfo) {
            if (device?.info?.id == info.id) {
                closeDevice()
                dispatch { listener?.onMidiStatus(null) }
            }
        }
    }

    private val receiver = object : MidiReceiver() {
        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            parse(msg, offset, count)
        }
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun start() {
        try {
            midiManager.registerDeviceCallback(deviceCallback, handler)
        } catch (e: Exception) {
            Log.w(TAG, "MIDI callback registration failed", e)
        }
        rescan()
    }

    fun rescan() {
        val infos = try {
            midiManager.devices
        } catch (e: Exception) {
            Log.w(TAG, "MIDI enumeration failed", e)
            emptyArray()
        }
        val match = infos.firstOrNull { isFlx4(it) }
        if (match != null) {
            if (device == null) open(match)
        } else if (device == null) {
            dispatch { listener?.onMidiStatus(null) }
        }
    }

    fun close() {
        try {
            midiManager.unregisterDeviceCallback(deviceCallback)
        } catch (_: Exception) {
        }
        closeDevice()
    }

    /** Cached LED writes; repeated states do not spam the MIDI port. */
    fun setLed(channel: Int, note: Int, on: Boolean) {
        val key = (channel shl 8) or note
        if (ledCache[key] == on) return
        ledCache[key] = on
        sendNote(channel, note, if (on) 0x7F else 0x00)
    }

    fun setDeckPlay(deck: Int, on: Boolean) =
        setLed(if (deck == 0) MidiMap.CH_DECK_A else MidiMap.CH_DECK_B, MidiMap.NOTE_PLAY, on)

    fun setDeckCue(deck: Int, on: Boolean) =
        setLed(if (deck == 0) MidiMap.CH_DECK_A else MidiMap.CH_DECK_B, MidiMap.NOTE_CUE, on)

    fun setDeckSync(deck: Int, on: Boolean) =
        setLed(if (deck == 0) MidiMap.CH_DECK_A else MidiMap.CH_DECK_B, MidiMap.NOTE_SYNC, on)

    fun setDeckCueChannel(deck: Int, on: Boolean) = setLed(
        if (deck == 0) MidiMap.CH_DECK_A else MidiMap.CH_DECK_B,
        MidiMap.NOTE_CUE_CHANNEL,
        on
    )

    fun setPad(deck: Int, note: Int, on: Boolean) =
        setLed(if (deck == 0) MidiMap.CH_PAD_A else MidiMap.CH_PAD_B, note, on)

    private fun open(info: MidiDeviceInfo) {
        try {
            midiManager.openDevice(info, { opened ->
                if (opened == null) {
                    Log.w(TAG, "openDevice returned null")
                    return@openDevice
                }
                device = opened
                connectedName = nameOf(info)
                for (port in info.ports) {
                    when (port.type) {
                        MidiDeviceInfo.PortInfo.TYPE_OUTPUT -> {
                            val output = try {
                                opened.openOutputPort(port.portNumber)
                            } catch (e: Exception) {
                                Log.w(TAG, "Cannot open MIDI output port", e)
                                null
                            }
                            if (output != null) {
                                try {
                                    output.connect(receiver)
                                    receivers.add(output)
                                } catch (e: Exception) {
                                    Log.w(TAG, "Cannot connect MIDI receiver", e)
                                    try {
                                        output.close()
                                    } catch (_: Exception) {
                                    }
                                }
                            }
                        }

                        MidiDeviceInfo.PortInfo.TYPE_INPUT -> {
                            midiInput = try {
                                opened.openInputPort(port.portNumber)
                            } catch (e: Exception) {
                                Log.w(TAG, "Cannot open MIDI input port", e)
                                null
                            }
                        }
                    }
                }
                ledCache.clear()
                dispatch { listener?.onMidiStatus(connectedName) }
            }, handler)
        } catch (e: Exception) {
            Log.w(TAG, "MIDI open failed", e)
            dispatch { listener?.onMidiStatus(null) }
        }
    }

    private fun closeDevice() {
        receivers.forEach {
            try {
                it.close()
            } catch (_: Exception) {
            }
        }
        receivers.clear()
        try {
            midiInput?.close()
        } catch (_: Exception) {
        }
        midiInput = null
        try {
            device?.close()
        } catch (_: Exception) {
        }
        device = null
        connectedName = null
        ledCache.clear()
    }

    private fun sendNote(channel: Int, note: Int, velocity: Int) {
        val port = midiInput ?: return
        try {
            port.send(byteArrayOf((0x90 or (channel and 0x0F)).toByte(), note.toByte(), velocity.toByte()), 0, 3)
        } catch (e: Exception) {
            Log.w(TAG, "MIDI send failed", e)
        }
    }

    private fun parse(data: ByteArray, offset: Int, count: Int) {
        val end = (offset + count).coerceAtMost(data.size)
        var i = offset
        while (i < end) {
            val byte = data[i].toInt() and 0xFF
            if (byte >= 0xF8) {
                i++
                continue
            }
            if (byte and 0x80 != 0) {
                when (byte) {
                    0xF0 -> inSysex = true
                    0xF7 -> {
                        inSysex = false
                        parserStatus = 0
                    }
                    else -> {
                        if (!inSysex) {
                            parserStatus = byte
                            parserDataCount = 0
                        }
                    }
                }
                i++
                continue
            }
            if (inSysex || parserStatus == 0) {
                i++
                continue
            }
            if (parserDataCount == 0) {
                parserData1 = byte
                parserDataCount = 1
            } else {
                handleMessage(parserStatus, parserData1, byte)
                parserDataCount = 0
            }
            i++
        }
    }

    private fun handleMessage(status: Int, data1: Int, data2: Int) {
        val type = status and 0xF0
        val channel = status and 0x0F
        when (type) {
            0x90 -> if (data2 > 0) handleNoteOn(channel, data1)
            0x80 -> handleNoteOff(channel, data1)
            0xB0 -> handleControlChange(channel, data1, data2)
        }
    }

    private fun handleNoteOn(channel: Int, note: Int) {
        when (channel) {
            MidiMap.CH_DECK_A, MidiMap.CH_DECK_B -> handleDeckNote(channel, note)
            MidiMap.CH_MIXER -> when (note) {
                MidiMap.NOTE_BROWSE_PRESS, MidiMap.NOTE_BROWSE_SHIFT_PRESS ->
                    dispatch { listener?.onBrowsePress() }

                MidiMap.NOTE_LOAD_A -> dispatch { listener?.onLoad(0) }
                MidiMap.NOTE_LOAD_B -> dispatch { listener?.onLoad(1) }
            }

            MidiMap.CH_PAD_A -> handlePad(0, note, shift = false)
            MidiMap.CH_PAD_SHIFT_A -> handlePad(0, note, shift = true)
            MidiMap.CH_PAD_B -> handlePad(1, note, shift = false)
            MidiMap.CH_PAD_SHIFT_B -> handlePad(1, note, shift = true)
        }
    }

    private fun handleNoteOff(channel: Int, note: Int) {
        when (channel) {
            MidiMap.CH_DECK_A, MidiMap.CH_DECK_B -> {
                val deck = if (channel == MidiMap.CH_DECK_A) 0 else 1
                when (note) {
                    MidiMap.NOTE_SHIFT -> dispatch { listener?.onDeckShift(deck, false) }
                    MidiMap.NOTE_JOG_TOUCH, MidiMap.NOTE_JOG_SHIFT_TOUCH ->
                        dispatch { listener?.onDeckJogTouch(deck, false) }
                }
            }
        }
    }

    private fun handleDeckNote(channel: Int, note: Int) {
        val deck = if (channel == MidiMap.CH_DECK_A) 0 else 1
        when (note) {
            MidiMap.NOTE_PLAY -> dispatch { listener?.onDeckPlay(deck) }
            MidiMap.NOTE_CUE -> dispatch { listener?.onDeckCue(deck) }
            MidiMap.NOTE_SHIFT -> dispatch { listener?.onDeckShift(deck, true) }
            MidiMap.NOTE_SYNC -> dispatch { listener?.onDeckSync(deck) }
            MidiMap.NOTE_SYNC_LONG -> dispatch { listener?.onDeckSyncLong(deck) }
            MidiMap.NOTE_TEMPO_RANGE -> dispatch { listener?.onDeckTempoRange(deck) }
            MidiMap.NOTE_LOOP_IN -> dispatch { listener?.onLoopIn(deck) }
            MidiMap.NOTE_LOOP_OUT -> dispatch { listener?.onLoopOut(deck) }
            MidiMap.NOTE_RELOOP -> dispatch { listener?.onReloop(deck) }
            MidiMap.NOTE_CUE_CHANNEL -> dispatch { listener?.onDeckCueToggle(deck) }
            MidiMap.NOTE_QUANTIZE -> dispatch { listener?.onDeckQuantize(deck) }
            MidiMap.NOTE_HOT_CUE_MODE -> dispatch {
                listener?.onPadMode(deck, Deck.PAD_HOT_CUE)
            }

            MidiMap.NOTE_BEAT_LOOP_MODE -> dispatch {
                listener?.onPadMode(deck, Deck.PAD_BEAT_LOOP)
            }

            MidiMap.NOTE_BEAT_JUMP_MODE -> dispatch {
                listener?.onPadMode(deck, Deck.PAD_BEAT_JUMP)
            }

            MidiMap.NOTE_JOG_TOUCH, MidiMap.NOTE_JOG_SHIFT_TOUCH ->
                dispatch { listener?.onDeckJogTouch(deck, true) }
        }
    }

    private fun handlePad(deck: Int, note: Int, shift: Boolean) {
        dispatch { listener?.onPad(deck, note, shift) }
    }

    private fun handleControlChange(channel: Int, control: Int, value: Int) {
        when (channel) {
            MidiMap.CH_DECK_A, MidiMap.CH_DECK_B -> {
                val deck = if (channel == MidiMap.CH_DECK_A) 0 else 1
                when (control) {
                    MidiMap.CC_JOG_SIDE, MidiMap.CC_JOG_VINYL,
                    MidiMap.CC_JOG_BEND -> {
                        val delta = value - 64
                        if (delta != 0) dispatch { listener?.onDeckJog(deck, delta, false) }
                    }

                    MidiMap.CC_JOG_SHIFT -> {
                        val delta = value - 64
                        if (delta != 0) dispatch { listener?.onDeckJog(deck, delta, true) }
                    }

                    MidiMap.CC_TEMPO_MSB, MidiMap.CC_TEMPO_LSB -> {
                        val full = combine(control, value)
                        dispatch { listener?.onDeckTempo(deck, full) }
                    }

                    MidiMap.CC_VOLUME_MSB, MidiMap.CC_VOLUME_LSB -> {
                        val full = combine(control, value)
                        dispatch { listener?.onDeckVolume(deck, full) }
                    }

                    MidiMap.CC_TRIM_MSB, MidiMap.CC_TRIM_LSB -> {
                        val full = combine(control, value)
                        dispatch { listener?.onDeckTrim(deck, full) }
                    }

                    MidiMap.CC_EQ_HIGH_MSB, MidiMap.CC_EQ_HIGH_LSB -> {
                        val full = combine(control, value)
                        dispatch { listener?.onDeckEq(deck, 0, full) }
                    }

                    MidiMap.CC_EQ_MID_MSB, MidiMap.CC_EQ_MID_LSB -> {
                        val full = combine(control, value)
                        dispatch { listener?.onDeckEq(deck, 1, full) }
                    }

                    MidiMap.CC_EQ_LOW_MSB, MidiMap.CC_EQ_LOW_LSB -> {
                        val full = combine(control, value)
                        dispatch { listener?.onDeckEq(deck, 2, full) }
                    }
                }
            }

            MidiMap.CH_MIXER -> when (control) {
                MidiMap.CC_CROSSFADER_MSB, MidiMap.CC_CROSSFADER_LSB -> {
                    val full = combine(control, value)
                    dispatch { listener?.onCrossfader(full) }
                }

                MidiMap.CC_HEADPHONE_MIX_MSB, MidiMap.CC_HEADPHONE_MIX_LSB -> {
                    val full = combine(control, value)
                    dispatch { listener?.onHeadphoneMix(full) }
                }

                MidiMap.CC_FILTER_A_MSB, MidiMap.CC_FILTER_A_LSB -> {
                    val full = combine(control, value)
                    dispatch { listener?.onDeckFilter(0, full) }
                }

                MidiMap.CC_FILTER_B_MSB, MidiMap.CC_FILTER_B_LSB -> {
                    val full = combine(control, value)
                    dispatch { listener?.onDeckFilter(1, full) }
                }

                MidiMap.CC_BROWSE -> {
                    val delta = value - 64
                    if (delta != 0) dispatch { listener?.onBrowse(delta) }
                }
            }
        }
    }

    private fun combine(control: Int, value: Int): Int {
        val normalized = control and 0x7F
        val base = if (normalized >= 0x20) normalized - 0x20 else normalized
        if (normalized >= 0x20) valueLsb[base] = value else valueMsb[base] = value
        // A knob/fader normally sends MSB then LSB. Defaulting an unseen MSB
        // to the centre avoids a violent jump when the first message is an LSB.
        val msb = if (valueMsb[base] >= 0) valueMsb[base] else 64
        val lsb = if (valueLsb[base] >= 0) valueLsb[base] else 0
        return (msb shl 7) or lsb
    }

    private fun dispatch(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
    }

    private fun isFlx4(info: MidiDeviceInfo): Boolean {
        val name = nameOf(info)
        return name.contains("FLX4", ignoreCase = true) ||
            name.contains("DDJ-FLX", ignoreCase = true)
    }

    private fun nameOf(info: MidiDeviceInfo): String {
        return try {
            info.properties.getString(MidiDeviceInfo.PROPERTY_NAME)
                ?: info.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
                ?: "DDJ-FLX4"
        } catch (_: Exception) {
            "DDJ-FLX4"
        }
    }

    companion object {
        private const val TAG = "Flx4Midi"
    }
}
