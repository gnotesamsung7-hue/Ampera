package com.example.eyebot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanTiltControllerTest {
    @Test fun deadZoneHoldsStill() {
        val c = PanTiltController()
        c.step(PanTiltController.Behavior.TRACK, 0.05f, -0.05f, 0.1f)
        assertEquals(90f, c.pan, 0.001f)
        assertEquals(90f, c.tilt, 0.001f)
    }

    @Test fun turnsTowardsFaceAndRespectsInvert() {
        val c = PanTiltController()
        repeat(10) { c.step(PanTiltController.Behavior.TRACK, 1f, 0f, 0.1f) }
        assertTrue(c.pan > 140f)
        val inv = PanTiltController(PanTiltController.Config(invertPan = true))
        repeat(10) { inv.step(PanTiltController.Behavior.TRACK, 1f, 0f, 0.1f) }
        assertTrue(inv.pan < 40f)
    }

    @Test fun clampsToLimits() {
        val c = PanTiltController()
        repeat(200) { c.step(PanTiltController.Behavior.TRACK, 1f, 1f, 0.1f) }
        assertEquals(165f, c.pan, 0.001f)
        assertEquals(125f, c.tilt, 0.001f)
    }

    @Test fun commandsAreRateLimitedAndDeduplicated() {
        val c = PanTiltController()
        assertEquals("S90,90\n", c.forceCommand(0))
        assertNull(c.pendingCommand(100))                          // nothing changed
        c.step(PanTiltController.Behavior.TRACK, 1f, 0f, 0.1f)
        assertNull(c.pendingCommand(10))                           // too soon
        assertEquals("S97,90\n", c.pendingCommand(100))
    }

    @Test fun sleepDroopsAndCentres() {
        val c = PanTiltController()
        repeat(50) { c.step(PanTiltController.Behavior.TRACK, 1f, -1f, 0.1f) }
        repeat(200) { c.step(PanTiltController.Behavior.SLEEP, 0f, 0f, 0.1f) }
        assertEquals(90f, c.pan, 0.01f)
        assertEquals(70f, c.tilt, 0.01f)
    }

    @Test fun lineAssemblerSplitsLines() {
        val a = LineAssembler()
        assertEquals(emptyList<String>(), a.feed("TOUCH:HE".toByteArray()))
        assertEquals(listOf("TOUCH:HEAD", "BTN:2"), a.feed("AD\r\nBTN:2\n".toByteArray()))
    }
}
