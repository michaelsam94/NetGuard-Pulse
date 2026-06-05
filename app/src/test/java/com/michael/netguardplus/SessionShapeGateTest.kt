package com.michael.netguardplus

import com.michael.netguardplus.system.hotspot.limit.SessionShapeGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionShapeGateTest {

    @Test
    fun pauseForBlocksUntilElapsed() {
        SessionShapeGate.clear()
        SessionShapeGate.pauseFor(80)
        assertTrue(SessionShapeGate.isPaused())
        Thread.sleep(100)
        assertFalse(SessionShapeGate.isPaused())
    }

    @Test
    fun pauseForExtendsWindow() {
        SessionShapeGate.clear()
        SessionShapeGate.pauseFor(200)
        Thread.sleep(50)
        SessionShapeGate.pauseFor(200)
        assertTrue(SessionShapeGate.remainingMs() > 150)
        SessionShapeGate.clear()
    }
}
