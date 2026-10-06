package com.example.eyebot

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechFormatTest {
    @Test fun phoneNumbersInChunks() {
        assertEquals("Call 604... 283... 9124 okay?", SpeechFormat.forSpeech("Call 6042839124 okay?"))
        assertEquals("604... 283... 9124", SpeechFormat.forSpeech("604-283-9124"))
    }
    @Test fun websitesSpelled() {
        assertEquals("go to a-i dot com", SpeechFormat.forSpeech("go to ai.com"))
        assertEquals("fish dot audio", SpeechFormat.forSpeech("fish.audio"))
    }
    @Test fun abbreviationsSpelled() {
        assertEquals("show the q-r card", SpeechFormat.forSpeech("show the QR card"))
        assertEquals("OK cool", SpeechFormat.forSpeech("OK cool"))
    }
    @Test fun normalTextUntouched() {
        assertEquals("It's 3:05 PM.", SpeechFormat.forSpeech("It's 3:05 PM."))
        assertEquals("7 plus 5 is 12!", SpeechFormat.forSpeech("7 plus 5 is 12!"))
    }
}
