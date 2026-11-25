package io.github.timotheegrollier.flx4pocket

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import android.util.Log
import java.nio.channels.FileChannel
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
    val beatFx = FxUnit(Deck.SAMPLE_RATE)

    /** Rate of the output stream, and of everything rendered into it: the output's own rate. */
    @Volatile
    var sampleRate: Int = Deck.SAMPLE_RATE
        private set

    /** True when Android gave the stream its low-latency path. */
    @Volatile
    var lowLatency: Boolean = false
        private set

    @Volatile
    var fxTarget: Int = FX_MASTER
    @Volatile
    var crossfader: Float = 0.5f
    /**
     * Master and headphones each in mono over two channels: left master, right headphones.
     * What the FLX4 takes with its rear Android MONO/STEREO switch on MONO, the way round many
     * phones (the Pixel 9 family among them) that only deliver the first two USB channels and
     * leave the headphones silent; or a splitter cable on a phone's own jack. The output reopens.
     */
    @Volatile
    var splitCue: Boolean = false
        set(split) {
            if (field == split) return
            field = split
            if (running) selectOutput(device)
        }
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

    /** Measured delay from the mixer to the output, in ms; 0 until Android reports it. */
    @Volatile
    var latencyMs: Int = 0
        private set

    /** The recording in progress, or null. */
    @Volatile
    var recorder: Recorder? = null
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

    /** Records the master output, as it is sent out, into WAV files obtained from [newFile]. */
    fun startRecording(newFile: (part: Int) -> FileChannel) {
        if (recorder == null) recorder = Recorder(sampleRate, BLOCK, newFile = newFile)
    }

    /** Ends the recording and completes its file; returns it for its length and any error. */
    fun stopRecording(): Recorder? = recorder?.also {
        recorder = null
        it.stop()
    }

    fun close() {
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        stopRecording()
        stopOutput()
        decks.forEach { it.close() }
    }

    fun outputs(): List<AudioDeviceInfo> = try {
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.isSink }
    } catch (_: Exception) {
        emptyList()
    }

    /** A meter reading: up to a new peak at once, else down by [factor]; flushed to 0 before floats get denormal and slow. */
    private fun fall(peak: Float, shown: Float, factor: Float): Float = max(peak, shown * factor).let { if (it < 1e-6f) 0f else it }

    /** Peak level of a deck before its fader (after trim, EQ and filter), for the channel meters. */
    fun level(deck: Int): Float = levels[deck]

    fun setMasterDeck(index: Int) {
        masterDeckIndex = index.coerceIn(0, 1)
        decks[masterDeckIndex].releaseSync()
    }

    /** Routes the mix to [target], or to the system default output when null. */
    @Synchronized
    fun selectOutput(target: AudioDeviceInfo?) {
        stopOutput()
        val quad = target != null && hasFourChannels(target) && !splitCue
        try {
            val out = try {
                openTrack(if (quad) 4 else 2, target)
            } catch (e: Exception) {
                if (!quad) throw e
                Log.w(TAG, "4-channel output refused, using stereo", e)
                openTrack(2, target)
            }
            device = target
            track = out
            outputChannels = out.channelCount
            outputName = target?.productName?.toString()
            lowLatency = out.performanceMode == AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
            if (out.sampleRate != sampleRate) {
                sampleRate = out.sampleRate
                decks.forEach { it.setOutputRate(sampleRate) }
                beatFx.sampleRate = sampleRate
                // A WAV file has one rate: what was recorded is kept, and the screen says why it ended.
                recorder?.stop("the output changed to $sampleRate Hz")
            }
            error = null
            running = true
            worker = Thread({
                try {
                    mixLoop(out)
                } catch (e: Throwable) {
                    // A fault in the mixer must stop the sound, not the app: the screen says so and AUDIO restarts it.
                    Log.e(TAG, "Mixer stopped", e)
                    if (running) error = "Audio engine stopped (${e.javaClass.simpleName}): choose the output again in AUDIO"
                }
            }, "flx4-mixer").apply { start() }
        } catch (e: Exception) {
            error = "Audio output failed: ${e.message ?: e.javaClass.simpleName}"
            Log.e(TAG, "Could not start audio output", e)
        }
    }

    /**
     * Opens and starts the stream at the output's own rate: Android only gives its low-latency
     * path (some 60 ms less) to a stream it does not have to resample. The rates an output may
     * run at are tried, the likeliest first, and the first one that gets that path is kept.
     */
    private fun openTrack(channels: Int, target: AudioDeviceInfo?): AudioTrack {
        val likely = when {
            target != null && mixAtMusicRate(target, channels) -> Deck.SAMPLE_RATE
            // Android runs a USB output at the highest rate the interface offers.
            target != null -> target.sampleRates.filter { it in RATES }.maxOrNull()
            else -> audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()?.takeIf { it in RATES }
        } ?: RATES[0]
        var kept: AudioTrack? = null
        var failure: Exception? = null
        for (rate in listOf(likely) + RATES.filter { it != likely }) {
            val out = try {
                buildTrack(channels, target, rate)
            } catch (e: Exception) {
                failure = e
                continue
            }
            try {
                out.play() // routes it to the output, which settles what it is granted
            } catch (e: Exception) {
                failure = e
                out.release()
                continue
            }
            if (out.performanceMode == AudioTrack.PERFORMANCE_MODE_LOW_LATENCY) {
                kept?.release()
                return out
            }
            if (kept == null) kept = out else out.release()
        }
        return kept ?: throw failure ?: IllegalStateException("no output")
    }

    /**
     * Left alone, Android mixes a USB output at 48 kHz when the interface offers it (the FLX4
     * does) and resamples the music into it. From Android 14 an app may ask for that mixer to
     * run at the rate of the music instead: 44.1 kHz tracks then reach the output untouched.
     */
    private fun mixAtMusicRate(target: AudioDeviceInfo, channels: Int): Boolean {
        if (Build.VERSION.SDK_INT < 34 || !isUsbSink(target)) return false
        return try {
            val mixer = audioManager.getSupportedMixerAttributes(target).filter {
                it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_DEFAULT &&
                    it.format.sampleRate == Deck.SAMPLE_RATE && it.format.channelCount == channels
            }.maxByOrNull { MIXER_ENCODINGS.indexOf(it.format.encoding) } ?: return false
            audioManager.setPreferredMixerAttributes(MEDIA, target, mixer)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot choose the mixer rate", e)
            false
        }
    }

    private fun buildTrack(channels: Int, target: AudioDeviceInfo?, rate: Int): AudioTrack {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(rate)
            .apply {
                // An index mask addresses the interface's outputs 1-4 directly,
                // without the surround-sound meaning of a position mask.
                if (channels == 4) setChannelIndexMask(0xF)
                else setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            }
            .build()
        val stereoBytes = AudioTrack.getMinBufferSize(
            rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT
        )
        val out = AudioTrack.Builder()
            .setAudioAttributes(MEDIA)
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
        val rate = out.sampleRate
        val limiter = Limiter(rate)
        // Meters jump to a peak at once and fall back at METER_FALL_DB dB a second.
        val meterFall = Math.pow(10.0, -METER_FALL_DB * BLOCK / rate / 20.0).toFloat()
        var lastGainA = 0f
        var lastGainB = 0f

        // Latency tuning (as in Oboe): start with two blocks of buffer and add a block
        // after each underrun, so the mix plays as soon after a move as this output allows.
        var bufferFrames = out.setBufferSizeInFrames(BLOCK * 2).takeIf { it > 0 } ?: out.bufferSizeInFrames
        Log.i(TAG, "Output $rate Hz, $channels ch, low latency $lowLatency, buffer $bufferFrames of ${out.bufferCapacityInFrames}")
        var underruns = out.underrunCount
        val stamp = AudioTimestamp()
        var framesWritten = 0L
        latencyMs = 0

        while (running) {
            // As in rekordbox, a master deck that stops hands the role to the one still playing: the
            // deck on air never follows a track being loaded, cued or started under it. A deck the DJ
            // has synced is the exception: it follows the master the DJ picked, playing or not, so
            // starting it before the master does not break the pair.
            if (!decks[masterDeckIndex].playing && decks[1 - masterDeckIndex].playing &&
                !decks[1 - masterDeckIndex].syncEnabled
            ) setMasterDeck(1 - masterDeckIndex)
            // The follower renders first, while the master still stands where this block starts.
            if (masterDeckIndex == 0) {
                deckB.render(BLOCK, bL, bR, if (deckB.syncEnabled) deckA else null)
                deckA.render(BLOCK, aL, aR, null)
            } else {
                deckA.render(BLOCK, aL, aR, if (deckA.syncEnabled) deckB else null)
                deckB.render(BLOCK, bL, bR, null)
            }

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
                // The cue bus and the channel meters are tapped before the faders, like the
                // FLX4's own: a deck closed on the crossfader still shows, and cues, its level.
                cueL[i] = aL[i] * pflA + bL[i] * pflB
                cueR[i] = aR[i] * pflA + bR[i] * pflB
                peakA = max(peakA, max(abs(aL[i]), abs(aR[i])))
                peakB = max(peakB, max(abs(bL[i]), abs(bR[i])))
                val gA = lastGainA + stepA * (i + 1)
                val gB = lastGainB + stepB * (i + 1)
                aL[i] *= gA
                aR[i] *= gA
                bL[i] *= gB
                bR[i] *= gB
            }
            lastGainA = gainA
            lastGainB = gainB
            levels[0] = fall(peakA, levels[0], meterFall)
            levels[1] = fall(peakB, levels[1], meterFall)

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
            val tape = recorder?.take()
            for (i in 0 until BLOCK) {
                val l = aL[i] * master
                val r = aR[i] * master
                val g = limiter.gainFor(max(abs(l), abs(r)))
                val left = l * g
                val right = r * g
                // Read before the limiter, so red shows that it is at work: the master is too hot.
                peakL = max(peakL, abs(l))
                peakR = max(peakR, abs(r))
                if (tape != null) {
                    tape[i * 2] = left
                    tape[i * 2 + 1] = right
                }
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
            meterL = fall(peakL, meterL, meterFall)
            meterR = fall(peakR, meterR, meterFall)
            if (tape != null) recorder?.put(tape)

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
            framesWritten += BLOCK
            if (out.underrunCount > underruns && bufferFrames < out.bufferCapacityInFrames) {
                underruns = out.underrunCount
                bufferFrames = out.setBufferSizeInFrames(bufferFrames + BLOCK).takeIf { it > 0 } ?: bufferFrames
            }
            if (framesWritten % (BLOCK * 64) == 0L && out.getTimestamp(stamp)) {
                val heard = stamp.framePosition + (System.nanoTime() - stamp.nanoTime) * rate / 1_000_000_000L
                val ms = (framesWritten - heard) * 1000 / rate
                if (ms in 1..999) latencyMs = ms.toInt() // some outputs report stale timestamps
            }
        }
    }

    companion object {
        const val FX_DECK_A = 0
        const val FX_DECK_B = 1
        const val FX_MASTER = 2
        private const val BLOCK = 256

        /**
         * How fast the meters fall after a peak. It used to be about 160 dB/s: between two
         * updates of the FLX4's LEDs (50 ms) a peak had already dropped 8 dB.
         */
        private const val METER_FALL_DB = 24.0

        /** Rates the engine runs at: what phones and USB sound cards mix at. */
        private val RATES = listOf(48000, 44100)

        private val MEDIA = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        /** Sample formats a USB mixer may offer, worst to best. */
        private val MIXER_ENCODINGS = intArrayOf(
            AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_24BIT_PACKED,
            AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT
        )
        private const val TAG = "Flx4Audio"

        fun isUsbSink(info: AudioDeviceInfo): Boolean = info.isSink &&
            (info.type == AudioDeviceInfo.TYPE_USB_DEVICE || info.type == AudioDeviceInfo.TYPE_USB_HEADSET)

        fun hasFourChannels(info: AudioDeviceInfo): Boolean =
            info.channelCounts.any { it >= 4 } ||
                info.channelIndexMasks.any { Integer.bitCount(it) >= 4 } ||
                info.channelMasks.any { Integer.bitCount(it) >= 4 }
    }
}
