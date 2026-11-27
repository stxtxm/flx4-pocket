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
import android.os.Process
import android.util.Log

/**
 * USB MIDI transport for the DDJ-FLX4: finds the controller, feeds its bytes
 * to a [Flx4Decoder] on the port's own reader thread, so the listener hears a
 * move without waiting for the screen, and sends LED / meter messages back
 * from the main thread.
 */
class Flx4Midi(context: Context, private val listener: Flx4Listener) {

    private val midiManager: MidiManager? = context.getSystemService(MidiManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val decoder = Flx4Decoder(listener)

    private var device: MidiDevice? = null
    private var midiInput: MidiInputPort? = null
    private val outputs = mutableListOf<MidiOutputPort>()
    private var opening = false

    /** Last value sent per (status, data1), so periodic refreshes send only changes. */
    private val sent = HashMap<Int, Int>()

    val connected: Boolean get() = device != null

    private val deviceCallback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(info: MidiDeviceInfo) {
            if (device == null && isFlx4(info)) open(info)
        }

        override fun onDeviceRemoved(info: MidiDeviceInfo) {
            if (device?.info?.id == info.id) {
                closeDevice()
                listener.onMidiStatus(null)
            }
        }
    }

    /**
     * The keep-alive the Mixxx mapping sends every 200 ms (captured from DJ
     * software). The FLX4 answers it with the position of every fader and knob,
     * so the app starts from what the hardware shows; [Flx4Decoder] ignores
     * the repeats.
     */
    private val keepAlive = object : Runnable {
        override fun run() {
            midiInput?.let { runCatching { it.send(KEEP_ALIVE, 0, KEEP_ALIVE.size) } }
            handler.postDelayed(this, KEEP_ALIVE_MS)
        }
    }

    /** One per output port: each port has its own reader thread, which decodes and calls the listener. */
    private inner class Receiver : MidiReceiver() {
        private var boosted = false

        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            if (!boosted) {
                // The reader starts at normal priority, behind the screen and the track decoders.
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                boosted = true
            }
            synchronized(decoder) { decoder.feed(msg, offset, count) }
        }
    }

    fun start() {
        val manager = midiManager ?: return
        try {
            manager.registerDeviceCallback(deviceCallback, handler)
            @Suppress("DEPRECATION")
            manager.devices.firstOrNull { isFlx4(it) }?.let { open(it) }
        } catch (e: Exception) {
            Log.w(TAG, "MIDI setup failed", e)
        }
    }

    fun close() {
        try {
            midiManager?.unregisterDeviceCallback(deviceCallback)
        } catch (_: Exception) {
        }
        closeDevice()
    }

    /** Button LED on a zero-based MIDI channel. */
    fun setLed(channel: Int, note: Int, on: Boolean) =
        send(0x90 or (channel and 0x0F), note, if (on) 0x7F else 0x00)

    /** Pad LED; the shifted layer mirrors it so it survives holding SHIFT. */
    fun setPad(deck: Int, note: Int, on: Boolean) {
        val channel = if (deck == 0) MidiMap.CH_PAD_A else MidiMap.CH_PAD_B
        setLed(channel, note, on)
        setLed(channel + 1, note, on)
    }

    /** Channel level meter, from a linear peak (1 = full scale). */
    fun setLevel(deck: Int, peak: Float) = send(0xB0 or deck, MidiMap.CC_LEVEL_METER, MixerMath.ledLevel(peak))

    private fun send(status: Int, data1: Int, data2: Int) {
        val port = midiInput ?: return
        val key = (status shl 8) or data1
        if (sent[key] == data2) return
        sent[key] = data2
        try {
            port.send(byteArrayOf(status.toByte(), data1.toByte(), data2.toByte()), 0, 3)
        } catch (e: Exception) {
            Log.w(TAG, "MIDI send failed", e)
        }
    }

    private fun open(info: MidiDeviceInfo) {
        if (opening) return
        opening = true
        try {
            midiManager?.openDevice(info, { opened ->
                opening = false
                if (opened == null) {
                    Log.w(TAG, "openDevice returned null")
                    return@openDevice
                }
                device = opened
                for (port in info.ports) {
                    try {
                        when (port.type) {
                            // Android names ports from the device's point of view:
                            // its "output" is what we read, its "input" what we write.
                            MidiDeviceInfo.PortInfo.TYPE_OUTPUT ->
                                opened.openOutputPort(port.portNumber)?.let {
                                    it.connect(Receiver())
                                    outputs.add(it)
                                }

                            MidiDeviceInfo.PortInfo.TYPE_INPUT ->
                                if (midiInput == null) {
                                    midiInput = opened.openInputPort(port.portNumber)
                                }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Cannot open MIDI port ${port.portNumber}", e)
                    }
                }
                sent.clear()
                synchronized(decoder) { decoder.reset() }
                // "Track loaded" jog illumination doubles as a connection hello.
                send(0x90 or MidiMap.CH_LOADED, 0x00, 0x7F)
                send(0x90 or MidiMap.CH_LOADED, 0x01, 0x7F)
                handler.removeCallbacks(keepAlive)
                keepAlive.run()
                listener.onMidiStatus(nameOf(info))
            }, handler)
        } catch (e: Exception) {
            opening = false
            Log.w(TAG, "MIDI open failed", e)
        }
    }

    private fun closeDevice() {
        handler.removeCallbacks(keepAlive)
        outputs.forEach { runCatching { it.close() } }
        outputs.clear()
        runCatching { midiInput?.close() }
        midiInput = null
        runCatching { device?.close() }
        device = null
        sent.clear()
    }

    private fun isFlx4(info: MidiDeviceInfo): Boolean = nameOf(info).contains("FLX", ignoreCase = true)

    private fun nameOf(info: MidiDeviceInfo): String = try {
        info.properties.getString(MidiDeviceInfo.PROPERTY_NAME)
            ?: info.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
            ?: ""
    } catch (_: Exception) {
        ""
    }

    companion object {
        private const val TAG = "Flx4Midi"
        private const val KEEP_ALIVE_MS = 200L
        private val KEEP_ALIVE = byteArrayOf(
            0xF0.toByte(), 0x00, 0x40, 0x05, 0x00, 0x00, 0x04, 0x05, 0x00, 0x50, 0x02, 0xF7.toByte()
        )
    }
}
