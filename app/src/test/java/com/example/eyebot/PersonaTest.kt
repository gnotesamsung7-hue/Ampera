package com.example.eyebot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaTest {

    @Test fun ticsAreShownButNotSpoken() {
        assertEquals("Oh! Hello there.", AmperaPersona.spoken("[Servo click] Oh! Hello there."))
        assertEquals("Ah. Hi.", AmperaPersona.spoken("[Static feedback] Ah. Hi."))
        assertEquals("Plain line.", AmperaPersona.spoken("Plain line."))
    }

    @Test fun safetyLinesArePlain() {
        val safety = listOf(
            AmperaPersona.breakDue(), AmperaPersona.wakeUpDriver(), AmperaPersona.driverSleepy(),
            AmperaPersona.backSeat(), AmperaPersona.gettingHot(), AmperaPersona.coolDown(),
            AmperaPersona.batteryCritical(),
        )
        for (s in safety) {
            assertFalse("no tic in safety line: $s", s.contains('['))
            assertTrue("short safety line: $s", s.length <= 60)
        }
    }

    @Test fun everyLineHasWordsToSay() {
        repeat(200) {
            for (s in listOf(
                AmperaPersona.greeting(), AmperaPersona.musing(), AmperaPersona.personHi("Mark"),
                AmperaPersona.powerUp(), AmperaPersona.dizzy(), AmperaPersona.goodNight(),
                AmperaPersona.animal(PixelIcon.DOG, "dog"), AmperaPersona.yum("pizza"),
            )) assertTrue(s, AmperaPersona.spoken(s).isNotBlank())
        }
        assertTrue(AmperaPersona.personHi("Mark").contains("Mark"))
    }
}
