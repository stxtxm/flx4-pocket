package io.github.timotheegrollier.flx4pocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class MusicalKeyTest {

    @Test
    fun `camelot codes follow the wheel`() {
        assertEquals("8B", MusicalKey(0, false, 0.0).camelot) // C major
        assertEquals("8A", MusicalKey(9, true, 0.0).camelot) // A minor
        assertEquals("5A", MusicalKey(0, true, 0.0).camelot) // C minor
        assertEquals("11A", MusicalKey(6, true, 0.0).camelot) // F# minor
        assertEquals("6B", MusicalKey(10, false, 0.0).camelot) // A# major
        assertEquals("Am", MusicalKey(9, true, 0.0).name)
        assertEquals("F#", MusicalKey(6, false, 0.0).name)
    }

    @Test
    fun `a key survives a round trip through the cache format`() {
        val key = MusicalKey(3, true, 0.8125)
        assertEquals(key, MusicalKey.decode(key.encode()))
        assertNull(MusicalKey.decode(""))
        assertNull(MusicalKey.decode("12;true;0.5"))
    }

    @Test
    fun `silence has no key`() {
        assertNull(MusicalKeys.estimate(DoubleArray(12)))
    }

    @Test
    fun `an A major triad is read as A major`() {
        val key = keyOf(listOf(220.0, 277.18, 329.63))
        assertEquals(MusicalKey(9, false, key.strength), key)
    }

    @Test
    fun `a C minor triad is read as C minor`() {
        val key = keyOf(listOf(261.63, 311.13, 392.0))
        assertEquals(0, key.tonic)
        assertEquals(true, key.minor)
    }

    /** Runs three seconds of [frequencies] through the same chroma path the decoder feeds. */
    private fun keyOf(frequencies: List<Double>): MusicalKey {
        val rate = 44100
        val frames = rate * 3
        val stereo = FloatArray(frames * 2)
        for (i in 0 until frames) {
            val v = frequencies.sumOf { sin(2 * PI * it * i / rate) }.toFloat() / frequencies.size
            stereo[i * 2] = v
            stereo[i * 2 + 1] = v
        }
        val chroma = ChromaAccumulator(rate)
        chroma.add(stereo, frames)
        return MusicalKeys.estimate(chroma.profile)!!
    }
}
