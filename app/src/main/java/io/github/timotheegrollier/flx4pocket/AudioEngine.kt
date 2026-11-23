package io.github.timotheegrollier.flx4pocket

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import kotlin.math.abs
import kotlin.math.max

/**
 * A small sample-clock mixer: two decks are rendered into one AudioTrack, so
 * beat sync never depends on two independent players staying aligned.
 *
 * Output modes:
 *  - normal: the master bus in stereo;
 *  - split cue: master on the left channel, headphone cue on the right, for a
 *    stereo splitter cable on phones without a second output.
 */
class AudioEngine(context: Context) {

    val decks = arrayOf(Deck(0), Deck(1))

    @Volatile
    var crossfader: Float = 0.5f
    @Volatile
    var splitCue: Boolean = false
    @Volatile
    var masterGain: Float = 0.8f
    @Volatile
    var headphoneGain: Float = 0.75f
    @Volatile
    var headphoneMix: Float = 0.5f
    @Volatile
    var masterDeckIndex: Int = 0
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
    var routedDeviceName: String? = null
        private set

    private val appContext = context.applicationContext
    private var track: AudioTrack? = null
    private var worker: Thread? = null
    @Volatile
    private var running = false

    fun start() {
        if (running) return
        try {
            val minBuffer = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_FLOAT
            )
            val bufferBytes = max(16_384, minBuffer * 2)
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build()
            track = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferBytes)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            track?.play()
            running = true
            worker = Thread(::mixLoop, "flx4-mixer").apply { start() }
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
            Log.e(TAG, "Could not start audio output", e)
        }
    }

    fun setOutputDevice(device: AudioDeviceInfo?): Boolean {
        return try {
            val result = track?.setPreferredDevice(device) ?: false
            routedDeviceName = device?.productName?.toString()
            result
        } catch (e: Exception) {
            Log.w(TAG, "setPreferredDevice failed", e)
            false
        }
    }

    fun stop() {
        running = false
        worker?.let {
            try {
                it.join(1200)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        worker = null
        try {
            track?.pause()
            track?.flush()
            track?.release()
        } catch (_: Exception) {
        }
        track = null
        decks.forEach { it.close() }
    }

    fun close() {
        stop()
    }

    private fun mixLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val aL = FloatArray(BLOCK)
        val aR = FloatArray(BLOCK)
        val bL = FloatArray(BLOCK)
        val bR = FloatArray(BLOCK)
        val output = FloatArray(BLOCK * 2)
        val deckA = decks[0]
        val deckB = decks[1]
        val out = track ?: return

        while (running) {
            val masterForA = if (deckA.syncEnabled && masterDeckIndex == 1) deckB else null
            val masterForB = if (deckB.syncEnabled && masterDeckIndex == 0) deckA else null
            deckA.render(BLOCK, aL, aR, masterForA)
            deckB.render(BLOCK, bL, bR, masterForB)

            val cross = crossfader
            val gainA = MixerMath.faderGain(deckA.volume) * MixerMath.crossGain(cross, 0)
            val gainB = MixerMath.faderGain(deckB.volume) * MixerMath.crossGain(cross, 1)
            val cueA = if (deckA.cueEnabled) 1f else 0f
            val cueB = if (deckB.cueEnabled) 1f else 0f
            val mix = headphoneMix.coerceIn(0f, 1f)
            val master = masterGain.coerceIn(0f, 1f)
            val headphones = headphoneGain.coerceIn(0f, 1f)
            val split = splitCue
            var peakL = 0f
            var peakR = 0f

            for (i in 0 until BLOCK) {
                val masterL = aL[i] * gainA + bL[i] * gainB
                val masterR = aR[i] * gainA + bR[i] * gainB
                val cueL = aL[i] * cueA + bL[i] * cueB
                val cueR = aR[i] * cueA + bR[i] * cueB

                val left: Float
                val right: Float
                if (split) {
                    left = (masterL * master).coerceIn(-1f, 1f)
                    val cueOutL = cueL * (1f - mix) + masterL * mix
                    val cueOutR = cueR * (1f - mix) + masterR * mix
                    right = (((cueOutL + cueOutR) * 0.5f) * headphones).coerceIn(-1f, 1f)
                    output[i * 2] = left
                    output[i * 2 + 1] = right
                } else {
                    left = (masterL * master).coerceIn(-1f, 1f)
                    right = (masterR * master).coerceIn(-1f, 1f)
                    output[i * 2] = left
                    output[i * 2 + 1] = right
                }
                peakL = max(peakL, abs(left))
                peakR = max(peakR, abs(right))
            }

            meterL = meterL * 0.8f + peakL * 0.2f
            meterR = meterR * 0.8f + peakR * 0.2f

            val written = try {
                out.write(output, 0, output.size, AudioTrack.WRITE_BLOCKING)
            } catch (e: Exception) {
                Log.e(TAG, "Audio write failed", e)
                -1
            }
            if (written < 0) {
                error = "Audio output stopped (${out.state})"
                break
            }
            routedDeviceName = out.routedDevice?.productName?.toString()
        }
    }

    fun masterDeck(): Deck = decks[masterDeckIndex]

    fun setMasterDeck(index: Int) {
        masterDeckIndex = index.coerceIn(0, 1)
        decks[index.coerceIn(0, 1)].releaseSync()
    }

    fun toggleSplitCue() {
        splitCue = !splitCue
    }

    companion object {
        const val SAMPLE_RATE = 44100
        private const val BLOCK = 256
        private const val TAG = "Flx4Audio"
    }
}
