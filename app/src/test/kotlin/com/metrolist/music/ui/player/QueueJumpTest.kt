package com.metrolist.music.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Row 198: when the queue page's jump arrow shows and which way it points. */
class QueueJumpTest {
    @Test
    fun hiddenWhenTheWholeQueueFits() {
        assertNull(queueJumpDirection(atTop = true, atEnd = true, centreSong = 2, songCount = 5))
    }

    @Test
    fun hiddenForAnEmptyQueue() {
        assertNull(queueJumpDirection(atTop = true, atEnd = false, centreSong = 0, songCount = 0))
    }

    @Test
    fun atTheTopOnlyDown() {
        assertEquals(QueueJump.ToBottom, queueJumpDirection(atTop = true, atEnd = false, centreSong = 4, songCount = 100))
    }

    @Test
    fun atTheBottomOnlyUp() {
        assertEquals(QueueJump.ToTop, queueJumpDirection(atTop = false, atEnd = true, centreSong = 95, songCount = 100))
        // Even when the screen is so tall the centre is still in the top half.
        assertEquals(QueueJump.ToTop, queueJumpDirection(atTop = false, atEnd = true, centreSong = 10, songCount = 30))
    }

    @Test
    fun inBetweenItGoesToTheFarEnd() {
        assertEquals(QueueJump.ToBottom, queueJumpDirection(atTop = false, atEnd = false, centreSong = 10, songCount = 100))
        assertEquals(QueueJump.ToBottom, queueJumpDirection(atTop = false, atEnd = false, centreSong = 49, songCount = 100))
        // From the halfway song on, it points up.
        assertEquals(QueueJump.ToTop, queueJumpDirection(atTop = false, atEnd = false, centreSong = 50, songCount = 100))
        assertEquals(QueueJump.ToTop, queueJumpDirection(atTop = false, atEnd = false, centreSong = 3, songCount = 5))
        assertEquals(QueueJump.ToBottom, queueJumpDirection(atTop = false, atEnd = false, centreSong = 2, songCount = 5))
    }
}
