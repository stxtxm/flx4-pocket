package io.github.timotheegrollier.flx4pocket

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import kotlin.math.abs
import kotlin.math.max

/**
 * A sample-clock mixer: both decks are rendered into one AudioTrack, so beat
 * sync never depends on two independent players staying aligned.
 *
 * Output layouts:
 *  - stereo: the master bus;
 *  - split cue: master (mono) on the left, headphones (mono) on the right,
 *    for a splitter cable on a stereo output;
 *  - four channels: master on 1-2 and headphones on 3-4, used automatically
 *    when a USB interface such as the DDJ-FLX4 exposes four outputs.
 */
class AudioEngine(context: Context) {

    val decks = arrayOf(Deck(0), Deck(1))

    /** The Beat FX section; [fxTarget] selects what it processes. */
    val beatFx = FxUnit(SAMPLE_RATE)

    @Volatile
    var fxTarget: Int = FX_MASTER
    @Volatile
    var crossfader: Float = 0.5f
    @Volatile
    var splitCue: Boolean = false
    @Volatile
    var masterGain: Float = 0.8f
    @Volatile
    var headphoneGain: Float = 0.8f

    /** 0 = cue bus only, 1 = master only. */
    @Volatile
    var headphoneMix: Float = 0.5f
    @Volatile
    var masterCue: Boolean = false
    @Volatile
    var masterDeckIndex: Int = 0
        private set

    @Volatile
    var error: String? = null
        private set
    @Volatile
    var meterL: Float = 0f
        private set
    @Volatile
    var meterR: Float = 0f
        private set
    @Volatile
    var outputName: String? = null
        private set

    /** 2 or 4; four means the headphones have their own output pair. */
    @Volatile
    var outputChannels: Int = 2
        private set

    private val levels = FloatArray(2)
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var track: AudioTrack? = null
    private var worker: Thread? = null
    private var device: AudioDeviceInfo? = null

    @Volatile
    private var running = false

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<AudioDeviceInfo>) {
            // Plugging in a USB interface is a clear request to use it.
            added.firstOrNull { isUsbSink(it) }?.let { selectOutput(it) }
        }

        override fun onAudioDevicesRemoved(removed: Array<AudioDeviceInfo>) {
            val current = device ?: return
            if (removed.any { it.id == current.id }) {
                // Never fall back to the phone speaker at full volume mid-set.
                decks.forEach { it.pause() }
                selectOutput(null)
            }
        }
    }

    fun start() {
        // The callback fires once with the devices already present, which
        // routes to a controller that was plugged in before the app started.
        selectOutput(null)
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
    }

    fun close() {
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        stopOutput()
        decks.forEach { it.close() }
    }

    fun outputs(): List<AudioDeviceInfo> = try {
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.isSink }
    } catch (_: Exception) {
        emptyList()
    }

    /** Peak level of a deck after its fader, for the channel meters. */
    fun level(deck: Int): Float = levels[deck]

    fun setMasterDeck(index: Int) {
        masterDeckIndex = index.coerceIn(0, 1)
        decks[masterDeckIndex].releaseSync()
    }

    /** Routes the mix to [target], or to the system default output when null. */
    @Synchronized
    fun selectOutput(target: AudioDeviceInfo?) {
        stopOutput()
        val quad = target != null && hasFourChannels(target)
        try {
            val out = try {
                buildTrack(if (quad) 4 else 2, target)
            } catch (e: Exception) {
                if (!quad) throw e
                Log.w(TAG, "4-channel output refused, using stereo", e)
                buildTrack(2, target)
            }
            device = target
            track = out
            outputChannels = out.channelCount
            outputName = target?.productName?.toString()
            error = null
            out.play()
            running = true
            worker = Thread({ mixLoop(out) }, "flx4-mixer").apply { start() }
        } catch (e: Exception) {
            error = "Audio output failed: ${e.message ?: e.javaClass.simpleName}"
            Log.e(TAG, "Could not start audio output", e)
        }
    }

    private fun buildTrack(channels: Int, target: AudioDeviceInfo?): AudioTrack {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(SAMPLE_RATE)
            .apply {
                // An index mask addresses the interface's outputs 1-4 directly,
                // without the surround-sound meaning of a position mask.
                if (channels == 4) setChannelIndexMask(0xF)
                else setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            }
            .build()
        val stereoBytes = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT
        )
        val out = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(max(stereoBytes, BLOCK * 8) * channels)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        if (target != null && !out.setPreferredDevice(target)) {
            out.release()
            throw IllegalStateException("${target.productName} refused the stream")
        }
        return out
    }

    private fun stopOutput() {
        running = false
        worker?.let {
            try {
                it.join(1000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        worker = null
        track?.let { runCatching { it.pause(); it.flush(); it.release() } }
        track = null
    }

    private fun mixLoop(out: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val channels = out.channelCount
        val aL = FloatArray(BLOCK)
        val aR = FloatArray(BLOCK)
        val bL = FloatArray(BLOCK)
        val bR = FloatArray(BLOCK)
        val cueL = FloatArray(BLOCK)
        val cueR = FloatArray(BLOCK)
        val output = FloatArray(BLOCK * channels)
        val deckA = decks[0]
        val deckB = decks[1]
        val limiter = Limiter(SAMPLE_RATE)
        var lastGainA = 0f
        var lastGainB = 0f

        while (running) {
            deckA.render(BLOCK, aL, aR, if (deckA.syncEnabled && masterDeckIndex == 1) deckB else null)
            deckB.render(BLOCK, bL, bR, if (deckB.syncEnabled && masterDeckIndex == 0) deckA else null)

            val cross = crossfader
            val gainA = MixerMath.faderGain(deckA.volume) * MixerMath.crossGain(cross, 0)
            val gainB = MixerMath.faderGain(deckB.volume) * MixerMath.crossGain(cross, 1)
            // Faders glide across the block: a gain stepping every 5.8 ms buzzes and clicks on cuts.
            val stepA = (gainA - lastGainA) / BLOCK
            val stepB = (gainB - lastGainB) / BLOCK
            val pflA = if (deckA.cueEnabled) 1f else 0f
            val pflB = if (deckB.cueEnabled) 1f else 0f
            var peakA = 0f
            var peakB = 0f
            for (i in 0 until BLOCK) {
                // The cue bus is tapped before the faders, like a hardware mixer.
                cueL[i] = aL[i] * pflA + bL[i] * pflB
                cueR[i] = aR[i] * pflA + bR[i] * pflB
                val gA = lastGainA + stepA * (i + 1)
                val gB = lastGainB + stepB * (i + 1)
                aL[i] *= gA
                aR[i] *= gA
                bL[i] *= gB
                bR[i] *= gB
                peakA = max(peakA, abs(aL[i]))
                peakB = max(peakB, abs(bL[i]))
            }
            lastGainA = gainA
            lastGainB = gainB
            levels[0] = max(peakA, levels[0] * 0.9f)
            levels[1] = max(peakB, levels[1] * 0.9f)

            // Beat FX sit after the faders so an echo keeps ringing when a fader closes.
            val target = fxTarget
            if (target == FX_DECK_A) beatFx.process(aL, aR, BLOCK, deckA.effectiveBpm(), deckA.beatPosition())
            if (target == FX_DECK_B) beatFx.process(bL, bR, BLOCK, deckB.effectiveBpm(), deckB.beatPosition())
            for (i in 0 until BLOCK) {
                aL[i] += bL[i]
                aR[i] += bR[i]
            }
            if (target == FX_MASTER) {
                val master = decks[masterDeckIndex]
                beatFx.process(aL, aR, BLOCK, master.effectiveBpm(), master.beatPosition())
            }

            val master = masterGain.coerceIn(0f, 1f)
            val phones = headphoneGain.coerceIn(0f, 1f)
            val mix = headphoneMix.coerceIn(0f, 1f)
            val masterToCue = if (masterCue) 1f else 0f
            val split = splitCue && channels == 2
            var peakL = 0f
            var peakR = 0f
            for (i in 0 until BLOCK) {
                val l = aL[i] * master
                val r = aR[i] * master
                val g = limiter.gainFor(max(abs(l), abs(r)))
                val left = l * g
                val right = r * g
                peakL = max(peakL, abs(left))
                peakR = max(peakR, abs(right))
                val o = i * channels
                if (channels == 2 && !split) {
                    output[o] = left
                    output[o + 1] = right
                    continue
                }
                val phonesL = ((cueL[i] + aL[i] * masterToCue) * (1f - mix) + aL[i] * mix) * phones
                val phonesR = ((cueR[i] + aR[i] * masterToCue) * (1f - mix) + aR[i] * mix) * phones
                if (split) {
                    output[o] = (left + right) * 0.5f
                    output[o + 1] = MixerMath.softClip((phonesL + phonesR) * 0.5f)
                } else {
                    output[o] = left
                    output[o + 1] = right
                    output[o + 2] = MixerMath.softClip(phonesL)
                    output[o + 3] = MixerMath.softClip(phonesR)
                }
            }
            meterL = max(peakL, meterL * 0.9f)
            meterR = max(peakR, meterR * 0.9f)

            val written = try {
                out.write(output, 0, output.size, AudioTrack.WRITE_BLOCKING)
            } catch (e: Exception) {
                Log.e(TAG, "Audio write failed", e)
                -1
            }
            if (written < 0) {
                if (running) error = "Audio output stopped (error $written)"
                break
            }
        }
    }

    companion object {
        const val SAMPLE_RATE = Deck.SAMPLE_RATE
        const val FX_DECK_A = 0
        const val FX_DECK_B = 1
        const val FX_MASTER = 2
        private const val BLOCK = 256
        private const val TAG = "Flx4Audio"

        fun isUsbSink(info: AudioDeviceInfo): Boolean = info.isSink &&
            (info.type == AudioDeviceInfo.TYPE_USB_DEVICE || info.type == AudioDeviceInfo.TYPE_USB_HEADSET)

        fun hasFourChannels(info: AudioDeviceInfo): Boolean =
            info.channelCounts.any { it >= 4 } ||
                info.channelIndexMasks.any { Integer.bitCount(it) >= 4 } ||
                info.channelMasks.any { Integer.bitCount(it) >= 4 }
    }
}
