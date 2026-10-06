package com.example.eyebot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCommandTest {
    @Test fun plainEmotionNames() {
        assertEquals(QrCommand.SetEmotion(Emotion.HAPPY), QrCommand.parse("HAPPY"))
        assertEquals(QrCommand.SetEmotion(Emotion.CURIOUS), QrCommand.parse(" curious "))
        assertEquals(QrCommand.SetEmotion(Emotion.SLEEPY), QrCommand.parse("Sleepy"))
        assertEquals(QrCommand.SetEmotion(Emotion.EXCITED), QrCommand.parse("EYEBOT:EXCITED"))
        assertEquals(QrCommand.SetEmotion(Emotion.LOVESTRUCK), QrCommand.parse("emotion:love"))
        assertEquals(QrCommand.SetEmotion(Emotion.SURPRISED), QrCommand.parse("eyebot://startled"))
    }

    @Test fun gameCards() {
        assertEquals(QrCommand.FeedMe, QrCommand.parse("FEED ME"))
        assertEquals(QrCommand.FeedMe, QrCommand.parse("feed-me"))
        assertEquals(QrCommand.Food("pizza"), QrCommand.parse("FOOD:PIZZA"))
        assertEquals(QrCommand.Food("apple"), QrCommand.parse("apple"))
        assertEquals(QrCommand.Food("food"), QrCommand.parse("FOOD"))
        assertEquals(QrCommand.Sleep, QrCommand.parse("SLEEP"))
        assertEquals(QrCommand.Sleep, QrCommand.parse("good night"))
        assertEquals(QrCommand.Wake, QrCommand.parse("WAKE_UP"))
        assertEquals(QrCommand.Party, QrCommand.parse("party mode"))
        assertEquals(QrCommand.Party, QrCommand.parse("MODE:PARTY"))
    }

    @Test fun colours() {
        val red = QrCommand.parse("COLOR:RED") as QrCommand.SetColor
        assertEquals(0xFFFF3B3B.toInt(), red.argb)
        val hex = QrCommand.parse("colour:#00ffaa") as QrCommand.SetColor
        assertEquals(0xFF00FFAA.toInt(), hex.argb)
        assertEquals(QrCommand.SetColor(null, "default"), QrCommand.parse("COLOR:RESET"))
        assertNull(QrCommand.parse("COLOR:sparkly"))
    }

    @Test fun sayKeepsOriginalCase() {
        assertEquals(QrCommand.Say("Hello, Mark!"), QrCommand.parse("SAY:Hello, Mark!"))
        assertNull(QrCommand.parse("SAY:"))
    }

    @Test fun unknownPayloads() {
        assertNull(QrCommand.parse("https://example.com"))
        assertNull(QrCommand.parse("random text"))
        assertNull(QrCommand.parse(""))
        assertNull(QrCommand.parse(null))
    }

    @Test fun everyEmotionRoundTrips() {
        // "PARTY" on its own is the Party-mode game card; EMOTION:PARTY just shows the face.
        for (e in Emotion.entries - Emotion.PARTY) assertEquals(QrCommand.SetEmotion(e), QrCommand.parse(e.name))
        for (e in Emotion.entries) assertEquals(QrCommand.SetEmotion(e), QrCommand.parse("EMOTION:${e.name}"))
        assertTrue(Emotion.entries.size >= 19)
    }
}
