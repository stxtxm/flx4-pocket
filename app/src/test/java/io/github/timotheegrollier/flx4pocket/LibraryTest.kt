package io.github.timotheegrollier.flx4pocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryTest {

    @Test
    fun `folder column is a tree with counts under each folder`() {
        val rows = Library.folderRows(
            listOf("Music/House", "Music/House/Deep", "Music/techno", "Music House", "Music/House", Library.ADDED_FILES),
            "All tracks"
        )
        assertEquals(
            listOf(
                "|All tracks|0|6",
                "Added tracks|Added tracks|0|1",
                "Music|Music|0|4",
                "Music/House|House|1|3",
                "Music/House/Deep|Deep|2|1",
                "Music/techno|techno|1|1",
                "Music House|Music House|0|1" // a sibling, not a child of Music
            ),
            rows.map { "${it.path}|${it.label}|${it.depth}|${it.count}" }
        )
    }

    @Test
    fun `a folder holds its subfolders but not a lookalike name`() {
        assertTrue(Library.inFolder("Music/House/Deep", "Music/House"))
        assertTrue(Library.inFolder("Music/House", "Music/House"))
        assertTrue(Library.inFolder("Anything", ""))
        assertFalse(Library.inFolder("Music/Housework", "Music/House"))
        assertFalse(Library.inFolder("Music", "Music/House"))
    }
}
