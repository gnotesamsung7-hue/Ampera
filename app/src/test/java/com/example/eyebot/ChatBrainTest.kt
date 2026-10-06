package com.example.eyebot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ChatBrainTest {
    private val ctx = ChatContext(hour = 15, minute = 5, dayOfWeek = 3, person = "Mark", battery = 76)
    private fun brain(seed: Int = 1) = ChatBrain(MapChatMemory(), Random(seed))
    private fun ChatBrain.say(s: String, c: ChatContext = ctx) = reply(listOf(s), c)

    @Test fun jokeIsAJoke() {
        val r = brain().say("tell me a joke")
        assertTrue(r.text in ChatBrain.JOKES)
    }

    @Test fun riddleRightAnswer() {
        val b = brain()
        val q = b.say("give me a riddle")
        assertTrue(q.listenAgain)
        val riddle = ChatBrain.RIDDLES.first { it.question == q.text }
        val a = b.say("is it a ${riddle.accept.first()}")
        assertTrue(a.text!!, a.text!!.contains("Correct") || a.text!!.startsWith("Yes"))
        // offered another round
        assertTrue(a.listenAgain)
        val again = b.say("yes")
        assertTrue(ChatBrain.RIDDLES.any { it.question == again.text })
    }

    @Test fun riddleGiveUp() {
        val b = brain(3)
        val q = b.say("riddle please")
        val riddle = ChatBrain.RIDDLES.first { it.question == q.text }
        val a = b.say("I give up")
        assertTrue(a.text!!.contains(riddle.answer))
        assertTrue(!b.expectsAnswer)
    }

    @Test fun riddleWrongThenReveal() {
        val b = brain(5)
        b.say("riddle")
        b.say("banana"); b.say("potato")
        val r = b.say("spaceship")
        assertTrue(r.text!!.contains("answer is"))
    }

    @Test fun userKnockKnock() {
        val b = brain()
        assertEquals("[Compass ping] Who's there?", b.say("knock knock").text)
        assertEquals("Lettuce who?", b.say("lettuce").text)
        val punch = b.say("lettuce in, it's cold out here")
        assertEquals(Emotion.HAPPY, punch.emotion)
    }

    @Test fun voltnuttKnockKnock() {
        val b = brain()
        assertEquals("Knock knock!", b.say("tell me a knock knock joke").text)
        val name = b.say("who's there").text!!.trimEnd('.')
        assertTrue(ChatBrain.KNOCKS.any { it.first == name })
        val punch = b.say("$name who").text
        assertEquals(ChatBrain.KNOCKS.first { it.first == name }.second, punch)
    }

    @Test fun numberGameHigherLower() {
        val b = ChatBrain(MapChatMemory(), Random(7))
        b.say("let's play guess my number")
        var won = false
        var lo = 1; var hi = 10
        repeat(4) {
            if (won) return@repeat
            val g = (lo + hi) / 2
            val r = b.say("$g").text!!
            when {
                r.startsWith("Yes") -> won = true
                r == "Higher!" -> lo = g + 1
                r == "Lower!" -> hi = g - 1
            }
        }
        assertTrue("binary search wins in 4", won)
    }

    @Test fun rockPaperScissors() {
        val b = brain()
        b.say("rock paper scissors")
        val r = b.say("paper")
        assertTrue(r.text!!.contains("I picked"))
        assertTrue(r.listenAgain)
    }

    @Test fun mathAndTime() {
        assertEquals("[Compass ping] 7 plus 5 is 12!", brain().say("what is 7 plus 5").text)
        assertEquals("[Compass ping] 3 times 4 is 12!", brain().say("what's three times four").text)
        assertEquals("It's 3:05 PM.", brain().say("what time is it").text)
        assertEquals("It's Tuesday.", brain().say("what day is it").text)
    }

    @Test fun commandsBecomeActions() {
        assertEquals(QrCommand.Sleep, brain().say("go to sleep").action)
        assertEquals(QrCommand.Party, brain().say("let's party").action)
        assertEquals(QrCommand.Food("pizza"), brain().say("do you want some pizza").action)
        assertNull(brain().say("let's party", ctx.copy(driving = true)).action)
    }

    @Test fun storyAdvancesAndIsRemembered() {
        val mem = MapChatMemory()
        val b = ChatBrain(mem, Random(1))
        val first = b.say("tell me a story").text!!
        assertTrue(first.startsWith("Factory One"))
        val second = b.say("yes").text!!
        assertTrue(second.startsWith("Factory Two"))
        assertEquals(2, mem.getInt(ChatBrain.KEY_STORY, 0))
        // a fresh brain with the same memory carries on
        assertTrue(ChatBrain(mem).say("story").text!!.startsWith("Factory Three"))
    }

    @Test fun feelingsAndLore() {
        assertEquals(Emotion.LOVESTRUCK, brain().say("I love you Voltnutt").emotion)
        assertEquals(Emotion.SAD, brain().say("you are stupid").emotion)
        assertTrue(brain().say("who is Thorne").text!!.contains("Thorne"))
        assertTrue(brain().say("kumusta").text!!.startsWith("Mabuti"))
        assertTrue(brain().say("hello").text!!.contains("Mark"))
    }

    @Test fun howAreYouThenAnswer() {
        val b = brain()
        val r = b.say("how are you")
        assertTrue(r.listenAgain)
        assertEquals(Emotion.HAPPY, b.say("pretty good thanks").emotion)
        assertEquals(Emotion.LOW_BATTERY, brain().say("how are you", ctx.copy(battery = 9)).emotion)
    }

    @Test fun fallbackForNonsense() {
        assertEquals(Emotion.CONFUSED, brain().say("the quantum walrus sells umbrellas").emotion)
    }

    @Test fun proactiveAlwaysSaysSomething() {
        val b = ChatBrain(MapChatMemory(), Random(11))
        repeat(300) {
            b.clearContext()
            val r = b.proactive(ctx.copy(hour = it % 24, dayOfWeek = 1 + it % 7))
            assertNotNull(r.text)
            assertTrue(AmperaPersona.spoken(r.text!!).isNotBlank())
            if (r.listenAgain) assertTrue(b.expectsAnswer)
        }
        val drive = b.proactive(ctx.copy(driving = true))
        assertTrue(!drive.listenAgain)
    }

    @Test fun proactiveQuestionGetsAnswered() {
        val b = ChatBrain(MapChatMemory(), Random(2))
        var r: ChatReply
        do { b.clearContext(); r = b.proactive(ctx) } while (!(r.text!!.contains("favourite food")))
        val a = b.say("I really like adobo").text!!
        assertTrue(a, a.contains("adobo"))
    }

    @Test fun wakeWord() {
        assertEquals("tell me a joke", ChatBrain.wakeRest("Hey Ampera tell me a joke"))
        assertEquals("what time is it", ChatBrain.wakeRest("hey am pera, what time is it"))
        assertEquals("", ChatBrain.wakeRest("ampere"))
        assertEquals("", ChatBrain.wakeRest("okay ampera"))
        assertNull(ChatBrain.wakeRest("I need a new camera"))
        assertNull(ChatBrain.wakeRest("what's for dinner"))
    }

    @Test fun amperaLore() {
        assertTrue(brain().say("who is Voltnutt").text!!.contains("Voltnutt"))
        assertTrue(brain().say("what's your name").text!!.contains("Ampera"))
    }

    @Test fun extractThing() {
        assertEquals("pizza", ChatBrain.extractThing("I like pizza"))
        assertEquals("blue", ChatBrain.extractThing("my favourite colour is blue!"))
        assertEquals("dogs", ChatBrain.extractThing("Dogs"))
    }
}
