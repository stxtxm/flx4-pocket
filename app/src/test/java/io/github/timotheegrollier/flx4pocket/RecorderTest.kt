package io.github.timotheegrollier.flx4pocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RecorderTest {

    private val files = ArrayList<File>()

    private fun recorder(maxFileBytes: Long = Recorder.WAV_LIMIT) = Recorder(48000, 256, maxFileBytes) { part ->
        val file = File.createTempFile("flx4-rec-$part-", ".wav").also { it.deleteOnExit() }
        files += file
        RandomAccessFile(file, "rw").channel
    }

    /** Hands over [blocks] blocks of a ramp, as the mixer does, waiting for a free block like a slow mixer would. */
    private fun feed(recorder: Recorder, blocks: Int) {
        var n = 0
        repeat(blocks) {
            var block = recorder.take()
            while (block == null) {
                Thread.sleep(1)
                block = recorder.take()
            }
            for (i in block.indices) block[i] = sample(n++)
            recorder.put(block)
        }
    }

    private fun sample(n: Int): Float = ((n % 2001) - 1000) / 1000f * 0.95f

    private fun read(file: File): ByteBuffer =
        ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)

    @Test
    fun `the file is a 24-bit stereo WAV holding the samples it was given`() {
        val recorder = recorder()
        feed(recorder, 40)
        recorder.stop()
        assertNull(recorder.error)
        assertEquals(40 * 256L, recorder.frames)

        val wav = read(files.single())
        val dataBytes = 40 * 256 * 2 * 3
        assertEquals(Recorder.HEADER_BYTES + dataBytes, wav.capacity())
        assertEquals("RIFF", String(wav.array(), 0, 4))
        assertEquals(36 + dataBytes, wav.getInt(4))
        assertEquals("WAVEfmt ", String(wav.array(), 8, 8))
        assertEquals("PCM", 1, wav.getShort(20).toInt())
        assertEquals("stereo", 2, wav.getShort(22).toInt())
        assertEquals(48000, wav.getInt(24))
        assertEquals(48000 * 6, wav.getInt(28))
        assertEquals(24, wav.getShort(34).toInt())
        assertEquals("data", String(wav.array(), 36, 4))
        assertEquals(dataBytes, wav.getInt(40))

        // Every sample comes back within one 24-bit step: far below anything audible.
        for (n in 0 until 40 * 256 * 2) {
            val at = Recorder.HEADER_BYTES + n * 3
            val value = (wav.get(at).toInt() and 0xFF) or ((wav.get(at + 1).toInt() and 0xFF) shl 8) or (wav.get(at + 2).toInt() shl 16)
            assertEquals(sample(n), value / 8388607f, 1.5f / 8388607f)
        }
    }

    @Test
    fun `a recording that outgrows a WAV file continues in the next part`() {
        val blockBytes = 256 * 2 * 3
        val recorder = recorder(maxFileBytes = Recorder.HEADER_BYTES + 10L * blockBytes)
        feed(recorder, 25)
        recorder.stop()
        assertEquals("nothing is lost at the seam", 25 * 256L, recorder.frames)
        assertEquals(listOf(10, 10, 5), files.map { (it.length().toInt() - Recorder.HEADER_BYTES) / blockBytes })
        files.forEach { assertEquals("each part has its own complete header", it.length().toInt() - Recorder.HEADER_BYTES, read(it).getInt(40)) }
    }

    @Test
    fun `a recording that cannot be written stops and says why`() {
        val recorder = Recorder(48000, 256) { throw java.io.IOException("No space left on device") }
        recorder.take()?.let(recorder::put)
        recorder.stop()
        assertEquals("No space left on device", recorder.error)
        assertNull("the mixer is no longer handed blocks", recorder.take())
        assertTrue(recorder.frames == 0L)
    }
}
