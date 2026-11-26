package com.coke.nestspace.core.files

import org.junit.Assert.assertEquals
import org.junit.Test

class PathNamesTest {

    @Test
    fun preservesSafeCharacters() {
        assertEquals("com.example.App_1-beta", safePathSegment("com.example.App_1-beta"))
    }

    @Test
    fun replacesEveryUnsafeCharacter() {
        assertEquals("a_b_c__d", safePathSegment("a/b:c @d"))
    }
}
