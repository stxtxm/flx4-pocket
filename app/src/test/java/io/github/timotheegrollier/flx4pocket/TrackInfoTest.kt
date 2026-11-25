package io.github.timotheegrollier.flx4pocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackInfoTest {

    private val info = TrackInfo(
        title = "Strobe; Remix|Edit", artist = "Deadmau5", album = "For Lack",
        genre = "House", durationSec = 612.5, codec = "MP3", sampleRate = 44100,
        channels = 2, bitrateKbps = 320
    )

    @Test
    fun `tags and format survive the cache`() {
        val decoded = TrackInfo.decode(info.encode())
        assertEquals(info.copy(title = "Strobe; Remix|Edit"), decoded)
    }

    @Test
    fun `the format line reads like a DJ would`() {
        assertEquals("MP3 · 44.1 kHz · stereo · 320 kbps", info.format)
        assertEquals("FLAC · 48.0 kHz · mono", info.copy(codec = "FLAC", sampleRate = 48000, channels = 1, bitrateKbps = 0).format)
    }

    @Test
    fun `codec names come from the container for uncompressed audio`() {
        assertEquals("AAC", TrackInfo.codecName("audio/mp4a-latm", "a.m4a"))
        assertEquals("AIFF", TrackInfo.codecName("audio/raw", "set.aiff"))
        assertEquals("WAV", TrackInfo.codecName("audio/raw", "set.wav"))
    }

    @Test
    fun `a damaged cache entry is ignored`() {
        assertNull(TrackInfo.decode("only one field"))
    }
}
