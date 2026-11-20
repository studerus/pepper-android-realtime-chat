package ch.fhnw.pepper_realtime.ui.compose

import org.junit.Assert.*
import org.junit.Test

class ChatControlsTest {
    @Test fun `reconnect and capture must both finish before the mic appears active`() {
        for (wanted in listOf(false, true)) {
            for (connected in listOf(false, true)) {
                for (capturing in listOf(false, true)) {
                    val expected = when {
                        !wanted -> MicrophoneVisualState.MUTED
                        connected && capturing -> MicrophoneVisualState.ACTIVE
                        else -> MicrophoneVisualState.PAUSED
                    }
                    assertEquals(expected, microphoneVisualState(wanted, connected, capturing))
                }
            }
        }
    }

    @Test fun `idle wait can be cleared and retyped without saving an invalid interval`() {
        assertEquals(90000L, ruleCooldownMillis("90", true))
        for (invalid in listOf("", "-1", "abc", "0", "0.999", "99999999999999999")) {
            assertNull(ruleCooldownMillis(invalid, true))
        }
        assertEquals(15000L, ruleCooldownMillis("15", true))
        assertEquals(0L, ruleCooldownMillis("0", false))
        assertEquals(250L, ruleCooldownMillis("0,250", false))
    }
}
