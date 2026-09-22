package com.smartview.glassai.services

import org.junit.Assert.*
import org.junit.Test

class WakeQuestionTest {
    @Test fun wakeAloneDoesNotRequestAPhoto() {
        val state = WakeQuestion()
        assertNull(state.accept("hey vision", true, 100))
        assertTrue(state.listening)
        assertEquals("what is the weather", state.accept("what is the weather", true, 1000))
    }
    @Test fun questionCanFollowWakeWithoutPause() {
        val state = WakeQuestion()
        assertNull(state.accept("hey vision", false, 0))
        assertEquals("read this sign", state.accept("hey vision read this sign", true, 1000))
    }
    @Test fun partialCommandsAreNeverExecuted() {
        val state = WakeQuestion()
        assertNull(state.accept("hey vision navigate to", false, 100))
        assertTrue(state.expired(20100))
    }
    @Test fun ordinaryConversationDoesNotActivate() {
        val state = WakeQuestion()
        assertNull(state.accept("what is the weather", true, 0))
        assertFalse(state.listening)
        assertFalse(state.expired(30000))
    }
}
