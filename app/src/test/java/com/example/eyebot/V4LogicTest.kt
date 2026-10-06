package com.example.eyebot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class V4LogicTest {
    // ---------------- face registry ----------------
    private fun vec(seed: Int, noise: Float = 0f, base: FloatArray? = null): FloatArray {
        val r = Random(seed)
        return FloatArray(192) { i -> (base?.get(i) ?: (r.nextFloat() - 0.5f)) + (r.nextFloat() - 0.5f) * noise }
    }

    @Test fun recognisesHouseholdAndRejectsStrangers() {
        val reg = FaceRegistry()
        val mark = vec(1); val ana = vec(2)
        reg.add("Mark", List(5) { vec(10 + it, 0.3f, mark) }, false, 0)
        reg.add("Ana", List(5) { vec(20 + it, 0.3f, ana) }, false, 0)
        assertEquals("Mark", reg.match(vec(99, 0.3f, mark)).person?.name)
        assertEquals("Ana", reg.match(vec(98, 0.3f, ana)).person?.name)
        assertNull(reg.match(vec(3)).person)                       // stranger
    }

    @Test fun lookAlikesFallBackToUnknown() {
        val reg = FaceRegistry()
        val a = vec(5)
        val twin = vec(6, 0.05f, a)
        reg.add("A", listOf(a), false, 0)
        reg.add("Twin", listOf(twin), false, 0)
        assertNull(reg.match(vec(7, 0.02f, a)).person)              // too close to call
    }

    @Test fun registrySurvivesSaveAndLoad() {
        val reg = FaceRegistry()
        val p = reg.add("Mark | Gabriel", listOf(vec(1), vec(2)), true, 1234)!!
        reg.update(p.id) { it.favoriteColor = 0xFFFF0000.toInt(); it.birthMonth = 10; it.birthDay = 2; it.melodyVariant = 3 }
        val again = FaceRegistry().apply { load(reg.serialize()) }
        val q = again.people().single()
        assertEquals("Mark   Gabriel", q.name)
        assertTrue(q.guest)
        assertEquals(0xFFFF0000.toInt(), q.favoriteColor)
        assertEquals(10, q.birthMonth); assertEquals(2, q.birthDay); assertEquals(3, q.melodyVariant)
        assertEquals(2, q.samples.size)
        assertEquals("Mark   Gabriel", again.match(vec(1)).person?.name)
        val r = again.add("New", listOf(vec(4)), false, 0)!!
        assertTrue(r.id > q.id)
    }

    @Test fun guestsExpireAndSamplesCapped() {
        val reg = FaceRegistry()
        val g = reg.add("Guest", listOf(vec(1)), true, 0)!!
        reg.add("Family", listOf(vec(2)), false, 0)
        assertEquals(listOf("Guest"), reg.expireGuests(8 * 86_400_000L))
        assertEquals(1, reg.size())
        val f = reg.people().single()
        repeat(40) { reg.recordSighting(f.id, vec(2, 0.01f), 0.9f, it * 61_000L, true) }
        assertEquals(FaceRegistry.MAX_SAMPLES, reg.find(f.id)!!.samples.size)
        assertNull(reg.find(g.id))
    }

    @Test fun voterNeedsAgreement() {
        val v = IdentityVoter()
        assertEquals(IdentityVoter.Verdict.Pending, v.vote(7))
        assertEquals(IdentityVoter.Verdict.Pending, v.vote(null))
        assertEquals(IdentityVoter.Verdict.Pending, v.vote(7))
        assertEquals(IdentityVoter.Verdict.Known(7), v.vote(7))
        val s = IdentityVoter()
        repeat(5) { s.vote(null) }
        assertEquals(IdentityVoter.Verdict.Stranger, s.verdict)
    }

    // ---------------- car ----------------
    @Test fun microsleepAndYawnsAreDetected() {
        val d = DrowsinessMonitor()
        var t = 0L
        val events = mutableListOf<DrowsinessMonitor.Event>()
        repeat(30) { t += 100; events += d.update(t, 0.9f, 0.05f) }
        assertTrue(events.isEmpty())
        repeat(15) { t += 100; events += d.update(t, 0.05f, 0.05f) }   // eyes shut 1.5 s
        assertTrue(DrowsinessMonitor.Event.MICROSLEEP in events)
        events.clear()
        repeat(3) { repeat(15) { t += 100; events += d.update(t, 0.9f, 0.6f) }; repeat(10) { t += 100; events += d.update(t, 0.9f, 0.05f) } }
        assertEquals(3, events.count { it == DrowsinessMonitor.Event.YAWN })
        assertTrue(DrowsinessMonitor.Event.DROWSY in events)
    }

    @Test fun heavyBlinkingOverAMinuteIsDrowsy() {
        val d = DrowsinessMonitor()
        var t = 0L
        val ev = mutableListOf<DrowsinessMonitor.Event>()
        repeat(600) { i -> t += 100; ev += d.update(t, if (i % 10 < 3) 0.1f else 0.9f, 0.05f) }   // eyes shut 30 % of the time
        assertTrue(DrowsinessMonitor.Event.DROWSY in ev)
        assertTrue(DrowsinessMonitor.Event.MICROSLEEP !in ev)
    }

    @Test fun unknownEyesNeverAlarm() {
        val d = DrowsinessMonitor()
        var t = 0L
        repeat(100) { t += 100; assertTrue(d.update(t, null, null).isEmpty()) }
    }

    @Test fun roadFeel() {
        val r = RoadFeel()
        assertEquals(RoadFeel.Event.BUMP, r.update(0, 0f, 0f, 9f))
        assertNull(r.update(100, 0f, 0f, 9f))                       // cooldown
        var e: RoadFeel.Event? = null
        var t = 5_000L
        repeat(20) { t += 50; e = e ?: r.update(t, 4.5f, 0f, 0f) }
        assertEquals(RoadFeel.Event.HARD_MANEUVER, e)
    }

    @Test fun shakeNeedsSeveralJolts() {
        val d = ShakeDetector()
        assertTrue(!d.update(0, 15f, 0f, 0f))                       // one jolt is a bump, not a shake
        assertTrue(!d.update(300, 0f, 15f, 0f))
        assertTrue(d.update(600, 15f, 0f, 0f))                       // third jolt within 1.2 s
        assertTrue(!d.update(900, 15f, 0f, 0f))                      // cooldown
        val e = ShakeDetector(); var t = 0L; var any = false
        repeat(100) { t += 50; any = any || e.update(t, 3f, 2f, 1f) } // driving vibration
        assertTrue(!any)
    }

    @Test fun tripTrackerDrivingParkedAndBreak() {
        val tr = TripTracker()
        val ev = mutableListOf<TripTracker.Event>()
        var t = 0L
        repeat(10) { t += 1000; ev += tr.update(t, 40f) }
        assertTrue(tr.driving && TripTracker.Event.STARTED_DRIVING in ev)
        repeat(30) { t += 1000; ev += tr.update(t, 0f) }            // red light: still driving
        assertTrue(tr.driving)
        repeat(40) { t += 1000; ev += tr.update(t, 0f) }
        assertTrue(!tr.driving && TripTracker.Event.PARKED in ev)
        repeat(200) { t += 1000; ev += tr.update(t, 0f) }
        assertTrue(TripTracker.Event.TRIP_ENDED in ev && !tr.inTrip)
        val tr2 = TripTracker(); t = 0; ev.clear()
        repeat(2 * 3600 + 20) { t += 1000; ev += tr2.update(t, 80f) }
        assertEquals(1, ev.count { it == TripTracker.Event.BREAK_DUE })
        assertNull(tr2.update(t + 1000, null).firstOrNull())
    }

    @Test fun powerWatch() {
        val p = PowerWatch()
        assertTrue(p.update(50, false, 30f).isEmpty())
        assertEquals(listOf(PowerWatch.Event.PLUGGED_IN), p.update(50, true, 30f))
        assertEquals(listOf(PowerWatch.Event.UNPLUGGED), p.update(50, false, 30f))
        assertEquals(listOf(PowerWatch.Event.LOW), p.update(15, false, 30f))
        assertTrue(p.update(14, false, 30f).isEmpty())
        assertEquals(listOf(PowerWatch.Event.CRITICAL), p.update(5, false, 30f))
        assertEquals(listOf(PowerWatch.Event.HOT), p.update(5, false, 43f))
        assertEquals(listOf(PowerWatch.Event.COOL_DOWN), p.update(5, false, 47f))
        assertTrue(p.update(5, false, 41f).isEmpty())                 // hysteresis
        assertEquals(listOf(PowerWatch.Event.COOLED), p.update(5, false, 39f))
    }

    // ---------------- mood / voice / icons ----------------
    @Test fun moodDriftsAndReacts() {
        val m = Mood(0.5f, 0.5f, 0.5f)
        repeat(5) { m.apply(Mood.Event.PETTED) }
        assertTrue(m.happiness > 0.75f)
        repeat(12) { m.tick(600f, 3f, alone = true) }                // 2 h alone at 3 am
        assertTrue(m.social < 0.2f && m.energy < 0.3f && m.isLonely && m.isTired)
        assertTrue(Mood.circadianEnergy(10f) > Mood.circadianEnergy(2f))
        assertEquals(m.serialize(), Mood.parse(m.serialize()).serialize())
        assertTrue(Mood(0.9f, 0.9f).voiceTone().pitch > Mood(0.1f, 0.1f).voiceTone().pitch)
    }

    @Test fun beepSpeechAndSignatures() {
        val q = BeepSpeech.render("Is that a doggy?", seed = 1)
        val e = BeepSpeech.render("Yay!", seed = 1)
        assertTrue(q.size > ToneSynth.SR / 4 && e.isNotEmpty())
        assertTrue(q.none { it.isNaN() } && q.maxOf { kotlin.math.abs(it) } <= 1f)
        assertEquals(3, BeepSpeech.syllables("banana"))
        assertEquals(1, BeepSpeech.syllables("cake"))
        val a = BeepSpeech.signatureNotes("Mark", 0)
        assertEquals(a, BeepSpeech.signatureNotes(" mark ", 0))      // stable per person
        assertTrue(a.size in 3..5)
        assertTrue(BeepSpeech.signatureNotes("Ana", 0) != a || BeepSpeech.signatureNotes("Mark", 1) != a)
        assertTrue(BeepSpeech.signature("Mark").size > 1000)
    }

    @Test fun iconsAndAnimals() {
        for (i in PixelIcon.entries) {
            assertEquals(12, i.grid.size)
            assertTrue(i.grid.all { it.length == 12 })
            assertTrue(i.grid.sumOf { r -> r.count { it == '#' } } > 20)
        }
        assertEquals(PixelIcon.DOG, PixelIcon.forLabel("dog"))
        assertEquals(PixelIcon.TEDDY, PixelIcon.forLabel("teddy bear"))
        assertNull(PixelIcon.forLabel("person"))
        assertEquals(Sfx.CAT, AnimalReactions.forLabel("cat")!!.sfx)
        assertNotNull(AnimalReactions.forLabel("giraffe"))
    }
}
