package dev.naominet.lazer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LazerLibraryTipCycleTest {
    @Test
    fun `library tips cycle through every entry without immediate repeats`() {
        val visited = buildList {
            var current = -1
            repeat(LAZER_LIBRARY_TIP_COUNT) {
                val next = nextLazerLibraryTipIndex(current)
                assertNotEquals(current, next)
                add(next)
                current = next
            }
        }
        assertEquals(LAZER_LIBRARY_TIP_COUNT, visited.toSet().size)
        assertTrue(visited.all { it in 0 until LAZER_LIBRARY_TIP_COUNT })
        assertEquals(visited.first(), nextLazerLibraryTipIndex(visited.last()))
    }
}
