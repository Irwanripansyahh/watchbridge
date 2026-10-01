package com.watchbridge.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionsTest {

    @Test
    fun `higher patch, minor or major is newer`() {
        assertTrue(isNewerVersion("0.1.3", "0.1.2"))
        assertTrue(isNewerVersion("0.2.0", "0.1.9"))
        assertTrue(isNewerVersion("1.0.0", "0.9.9"))
    }

    @Test
    fun `numbers are compared numerically, not as text`() {
        assertTrue(isNewerVersion("0.1.10", "0.1.9"))
        assertFalse(isNewerVersion("0.1.9", "0.1.10"))
    }

    @Test
    fun `same or older version is not newer`() {
        assertFalse(isNewerVersion("0.1.2", "0.1.2"))
        assertFalse(isNewerVersion("0.1.1", "0.1.2"))
    }

    @Test
    fun `release tag prefix and build suffix are ignored`() {
        assertFalse(isNewerVersion("v0.1.2", "0.1.2"))
        assertTrue(isNewerVersion("v0.1.3", "0.1.2-debug"))
    }

    @Test
    fun `missing parts count as zero`() {
        assertFalse(isNewerVersion("0.2", "0.2.0"))
        assertTrue(isNewerVersion("0.2.1", "0.2"))
    }
}
