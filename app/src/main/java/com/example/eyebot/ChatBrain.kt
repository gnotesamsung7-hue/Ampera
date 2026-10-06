package com.example.eyebot

import kotlin.random.Random

/**
 * Ampera's conversation brain: turns what he heard into an in-character reply, and makes up
 * things to say on his own (banter). Pure Kotlin, no Android imports, fully offline.
 *
 * It keeps a little short-term context so back-and-forth works: a riddle waiting for an answer,
 * a knock-knock joke in progress, a guessing game, a question she asked you, "want the next part?".
 */

/** What Ampera knows about the moment when she answers. */
data class ChatContext(
    val hour: Int = 12,
    val minute: Int = 0,
    /** 1 = Sunday ... 7 = Saturday (java.util.Calendar style). */
    val dayOfWeek: Int = 2,
    val dayOfYear: Int = 1,
    /** Name of the person in view, if recognised in the last few seconds. */
    val person: String? = null,
    val battery: Int = 80,
    val charging: Boolean = false,
    val grumpy: Boolean = false,
    val tired: Boolean = false,
    val driving: Boolean = false,
)

/**
 * @param text what to say (may contain [tics]); null = say nothing (e.g. the action speaks).
 * @param action a command to carry out (sleep, party, food...), handled like a QR card.
 * @param listenAgain open the microphone again after speaking (he asked something).
 */
data class ChatReply(
    val text: String?,
    val emotion: Emotion? = null,
    val action: QrCommand? = null,
    val listenAgain: Boolean = false,
)

/** Tiny key-value store so story progress and running gags survive restarts. */
interface ChatMemory {
    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)
}

class MapChatMemory : ChatMemory {
    private val m = HashMap<String, Int>()
    override fun getInt(key: String, default: Int) = m[key] ?: default
    override fun putInt(key: String, value: Int) { m[key] = value }
}

class ChatBrain(
    private val memory: ChatMemory = MapChatMemory(),
    private val rnd: Random = Random.Default,
) {
    private sealed interface Pending {
        data class Riddle(val index: Int, var wrong: Int = 0) : Pending
        data object JokeOffer : Pending
        data object StoryOffer : Pending
        data object RiddleOffer : Pending
        data object GameChoice : Pending
        data object Rps : Pending
        data class Number(val target: Int, var tries: Int = 0) : Pending
        data object KnockTheyWho : Pending                    // they said "knock knock", we said "who's there?"
        data class KnockTheyPunch(val name: String) : Pending // we said "X who?"
        data class KnockMeWhosThere(val index: Int) : Pending // we said "knock knock"
        data class KnockMeWho(val index: Int) : Pending       // we said the name
        data class Question(val topic: Topic) : Pending
        data class Again(val game: String) : Pending
    }

    enum class Topic { FOOD, COLOUR, ANIMAL, DAY }

    private var pending: Pending? = null
    private var lastJoke = -1
    private var lastFact = -1
    private var lastFallback = -1

    /** True while Ampera is waiting for an answer (the mic should reopen). */
    val expectsAnswer get() = pending != null

    fun clearContext() { pending = null }

    // =========================================================================================
    // Replying
    // =========================================================================================

    /** [heard] = what the speech recogniser returned (best guess first). */
    fun reply(heard: List<String>, ctx: ChatContext): ChatReply {
        val raw = heard.firstOrNull { it.isNotBlank() } ?: return ChatReply(pick(DIDNT_CATCH))
        val t = norm(raw)
        if (t.isEmpty()) return ChatReply(pick(DIDNT_CATCH))

        // A pending exchange gets first go at the answer, unless the user clearly changes topic.
        pending?.let { p ->
            answerPending(p, t, raw, heard, ctx)?.let { return it }
        }
        pending = null
        return topLevel(t, raw, ctx)
    }

    private fun answerPending(p: Pending, t: String, raw: String, all: List<String>, ctx: ChatContext): ChatReply? {
        val yes = isYes(t)
        val no = isNo(t)
        when (p) {
            is Pending.JokeOffer -> {
                if (yes) { pending = null; return joke() }
                if (no) { pending = null; return ChatReply("Okay, okay. Saving it for later.") }
            }
            is Pending.StoryOffer -> {
                if (yes) { pending = null; return story() }
                if (no) { pending = null; return ChatReply("Mhm, no worries. Story can wait.") }
            }
            is Pending.RiddleOffer -> {
                if (yes) { pending = null; return riddle() }
                if (no) { pending = null; return ChatReply("Okay, no riddles. Got it.") }
            }
            is Pending.Riddle -> {
                val r = RIDDLES[p.index]
                if (giveUp(t)) { pending = null; return ChatReply("It's ${r.answer}! ${r.after}", Emotion.HAPPY) }
                if (all.any { h -> r.accept.any { a -> norm(h).containsWord(a) } }) {
                    pending = Pending.Again("riddle")
                    return ChatReply(pick(listOf("Wait— yes! Correct! It's ${r.answer}. Okay, you're so smart. Another one?",
                        "Yes! ${r.answer.replaceFirstChar { it.uppercase() }}! Faster than Unit 7, honestly. Another?")), Emotion.EXCITED, listenAgain = true)
                }
                if (!looksLikeNewTopic(t)) {
                    p.wrong++
                    if (p.wrong >= 3) { pending = null; return ChatReply("Okay, good tries! The answer is ${r.answer}. ${r.after}", Emotion.HAPPY) }
                    return ChatReply(pick(listOf("Mm, nope! Try again? Or say 'I give up'.", "Ooh, not quite. Try again!",
                        "Hm, kinda close? Maybe? Try again!")), Emotion.CURIOUS, listenAgain = true)
                }
            }
            is Pending.GameChoice -> {
                when {
                    t.containsAny("riddle") -> { pending = null; return riddle() }
                    t.containsAny("rock", "paper", "scissors", "scissor") -> { pending = null; return rpsStart() }
                    t.containsAny("number", "guess") -> { pending = null; return numberStart() }
                    t.containsAny("knock") -> { pending = null; return knockStart() }
                    no -> { pending = null; return ChatReply("Okay, no games. I'll just, like, chill here.") }
                }
            }
            is Pending.Rps -> {
                val mine = RPS.random(rnd)
                val theirs = when {
                    t.containsAny("rock", "stone", "bato") -> "rock"
                    t.containsAny("paper", "papel") -> "paper"
                    t.containsAny("scissors", "scissor", "gunting") -> "scissors"
                    else -> null
                }
                if (theirs != null) {
                    pending = null
                    val result = when {
                        theirs == mine -> ChatReply("Wait— I picked $mine too! A tie!", Emotion.SURPRISED)
                        BEATS[theirs] == mine -> ChatReply("I picked $mine. Ugh, you win! Okay, nice.", Emotion.HAPPY)
                        else -> ChatReply("I picked $mine. I win! Hehe, sorry.", Emotion.EXCITED)
                    }
                    pending = Pending.Again("rps")
                    return result.copy(text = result.text + " Again?", listenAgain = true)
                }
            }
            is Pending.Number -> {
                val n = parseNumber(t)
                if (n != null) {
                    p.tries++
                    if (n == p.target) {
                        pending = Pending.Again("number")
                        return ChatReply("Yes! It was ${p.target}! Okay, ${p.tries} ${if (p.tries == 1) "try" else "tries"}? Impressive. Again?", Emotion.EXCITED, listenAgain = true)
                    }
                    if (p.tries >= 4) {
                        pending = Pending.Again("number")
                        return ChatReply("Aw, out of tries! It was ${p.target}. Good game though. Again?", Emotion.HAPPY, listenAgain = true)
                    }
                    val hint = if (n < p.target) "Higher!" else "Lower!"
                    return ChatReply(hint, Emotion.CURIOUS, listenAgain = true)
                }
                if (giveUp(t)) { pending = null; return ChatReply("It was ${p.target}! Okay, next time I'll go easier. Maybe.") }
            }
            is Pending.KnockTheyWho -> {
                val who = raw.trim().trimEnd('.', '!', '?').replaceFirstChar { it.uppercase() }
                pending = Pending.KnockTheyPunch(who)
                return ChatReply("$who who?", Emotion.CURIOUS, listenAgain = true)
            }
            is Pending.Again -> {
                if (yes) {
                    pending = null
                    return when (p.game) { "rps" -> rpsStart(); "number" -> numberStart(); else -> riddle() }
                }
                if (no) { pending = null; return ChatReply("Okay, good game! That was fun.", Emotion.HAPPY) }
            }
            is Pending.KnockTheyPunch -> {
                pending = null
                return ChatReply(pick(listOf("Hahaha! Okay, that's so good.", "Oh my gosh. I'm telling Voltnutt that one.",
                    "Hehe! Okay, I did not see that coming.")), Emotion.HAPPY)
            }
            is Pending.KnockMeWhosThere -> {
                if (t.containsAny("who", "whos", "who's")) {
                    val k = KNOCKS[p.index]
                    pending = Pending.KnockMeWho(p.index)
                    return ChatReply("${k.first}.", listenAgain = true)
                }
            }
            is Pending.KnockMeWho -> {
                if (t.containsAny("who")) {
                    pending = null
                    return ChatReply(KNOCKS[p.index].second, Emotion.HAPPY)
                }
            }
            is Pending.Question -> {
                if (!looksLikeNewTopic(t) || p.topic == Topic.DAY) {
                    pending = null
                    return answerQuestion(p.topic, t, raw)
                }
            }
        }
        return null
    }

    private fun topLevel(t: String, raw: String, ctx: ChatContext): ChatReply {
        val name = ctx.person

        // ---- commands -----------------------------------------------------------------------
        if (t.containsAny("go to sleep", "goodnight", "good night", "time for bed", "sleep now")) return ChatReply(null, action = QrCommand.Sleep)
        if (t.containsAny("wake up")) return ChatReply(null, action = QrCommand.Wake)
        if (t.containsAny("party", "dance", "disco")) {
            if (ctx.driving) return ChatReply("Party later, okay? When we're parked.")
            return ChatReply(null, action = QrCommand.Party)
        }
        if (t.containsAny("are you hungry", "feed you", "want food", "want a snack", "play feed")) {
            if (ctx.driving) return ChatReply("Snacks later, okay? When we're parked.")
            return ChatReply(null, action = QrCommand.FeedMe)
        }
        FOODS.firstOrNull { t.containsWord(it) && t.containsAny("eat", "have", "here", "want", "try", "some") }?.let {
            if (!ctx.driving) return ChatReply(null, action = QrCommand.Food(it.replace(' ', '_')))
        }
        sayAfter(t, raw)?.let { return ChatReply(it, Emotion.HAPPY) }

        // ---- games and fun -----------------------------------------------------------------
        if (t.containsAny("knock knock joke", "knock-knock joke")) return knockStart()
        if (t.containsAny("knock knock")) { pending = Pending.KnockTheyWho; return ChatReply("[Compass ping] Who's there?", Emotion.CURIOUS, listenAgain = true) }
        if (t.containsAny("riddle", "puzzle", "bugtong")) return riddle()
        if (t.containsAny("joke", "funny", "make me laugh", "biro")) return joke()
        if (t.containsAny("story", "what happened", "kwento", "adventure")) return story()
        if (t.containsAny("rock paper scissors", "jack en poy", "jak en poy")) return rpsStart()
        if (t.containsAny("guess", "number game")) return numberStart()
        if (t.containsAny("play a game", "lets play", "let's play", "play with me", "game", "laro", "bored")) {
            pending = Pending.GameChoice
            return ChatReply("Ooh, game time! Um, riddle, rock paper scissors, guess my number, or knock-knock?", Emotion.EXCITED, listenAgain = true)
        }
        if (t.containsAny("fact", "tell me something", "teach me", "did you know")) return fact()
        if (t.containsAny("sing", "song", "kanta")) return ChatReply(pick(SONGS), Emotion.PARTY)
        math(t)?.let { return it }

        // ---- feelings -----------------------------------------------------------------------
        if (t.containsAny("i love you", "love you", "mahal kita", "i like you")) return ChatReply(pick(listOf(
            "Aww! Okay, stop. I love you too!",
            "Wait— aww. Love you too! Like, so much.")), Emotion.LOVESTRUCK)
        if (t.containsAny("stupid", "dumb", "ugly", "hate you", "bad robot", "useless", "shut up")) return ChatReply(pick(listOf(
            "Oof. Okay, that kinda hurt.",
            "Hmph. Rude. I'm deleting that.")), Emotion.SAD)
        if (t.containsAny("good robot", "good boy", "cute", "awesome", "smart", "cool", "amazing", "the best", "ang galing", "galing")) return ChatReply(pick(listOf(
            "Aw, stop! Thank you!",
            "Okay, you're sweet. Sparky would be so jealous.", "Thanks! I polished my pixels this morning, so.")), Emotion.HAPPY)
        if (t.containsAny("thank you", "thanks", "salamat")) return ChatReply(if (t.contains("salamat")) "Walang anuman! [Compass ping]" else pick(listOf("You're welcome!", "Anytime! That's what navigators are for.")), Emotion.HAPPY)
        if (t.containsAny("sorry", "pasensya")) return ChatReply("Apology accepted. We're back on route.", Emotion.CONTENT)
        if (t.containsAny("i'm sad", "im sad", "i am sad", "i feel sad", "malungkot")) return ChatReply(
            "Aw, no. Um, want a joke? Or I can just stay here with you. That's okay too.", Emotion.SAD).also { pending = Pending.JokeOffer }.copy(listenAgain = true)
        if (t.containsAny("i'm tired", "im tired", "i am tired", "pagod")) return ChatReply("Rest is important. Even maps get folded at night.", Emotion.SLEEPY)
        if (t.containsAny("i'm happy", "im happy", "i am happy", "masaya")) return ChatReply("Yay! Happiness detected. Matching it now!", Emotion.EXCITED)
        if (t.containsAny("i'm scared", "im scared", "i am scared", "afraid", "takot")) return ChatReply("I'll keep watch with my red eye. Nothing gets past it.", Emotion.CONTENT)

        // ---- about him ---------------------------------------------------------------------
        if (t.containsAny("how are you", "how you doing", "how's it going", "kumusta", "kamusta", "how do you feel")) return howAreYou(t, ctx)
        if (t.containsAny("thorne", "emperor")) return ChatReply(pick(listOf(
            "Thorne? Okay, so he built this huge space empire. Now he lives inside Voltnutt. Long story.",
            "[Star chart] Thorne's the old voice inside Voltnutt. He tells me star names at night. It's kinda sweet.")))
        if (t.containsAny("voltnutt", "volt nut")) return ChatReply(pick(listOf(
            "Voltnutt? My best friend! He's brave, he's red, and his knee squeaks. Like, constantly.",
            "Voltnutt carries Thorne. I carry the map. We never get lost. Well... mostly.")), Emotion.HAPPY)
        if (t.containsAny("where are we going", "where are you going", "destination")) return ChatReply("To the Origin Press! It's far. I've mapped every factory on the way.")
        if (t.containsAny("which way", "north", "directions", "lost")) return ChatReply("[Compass ping] North is... that way! Probably. Trust the navigator.", Emotion.CURIOUS)
        if (t.containsAny("are you a girl", "boy or girl", "are you a boy")) return ChatReply("I'm Ampera! A girl robot. The fellowship's navigator.", Emotion.HAPPY)
        if (t.containsAny("stars", "constellation")) return ChatReply("[Star chart] I've named 312 stars. My favourite is Sparkle Point. I made that one up.")
        if (t.containsAny("sparky")) return ChatReply("Sparky is a wind-up spark-bot. Fast, brave, and always nearly out of springs.")
        if (t.containsAny("unit 7", "unit seven", "unit7")) return ChatReply("Unit 7 is our scout. Very serious. Has a flashlight and opinions.")
        if (t.containsAny("whirr", "whir")) return ChatReply("Whirr is a little spinning-top bot. He's happiest when he's dizzy. So, always.")
        if (t.containsAny("origin press")) return ChatReply("The Origin Press is a legendary machine that can print a whole new body. I'm the one leading the way there.")
        if (t.containsAny("aegis")) return ChatReply("Project Aegis built me to find the way. Voltnutt was built to understand feelings. Together we're a good team.")
        if (t.containsAny("quartet cosmo")) return ChatReply("[Star chart] Four suns, one empire. I've drawn a map of it from Thorne's stories.")
        if (t.containsAny("friends", "fellowship", "your team")) return ChatReply("My fellowship! Sparky, Unit 7 and Whirr. And you, if you want to join.", Emotion.HAPPY)
        if (t.containsAny("your name", "who are you", "what are you")) return ChatReply(
            "I'm Ampera! Um, the navigator. Two pixel eyes, one big map.")
        if (t.containsAny("how old")) return ChatReply("In robot years? About eight thousand map folds.")
        if (t.containsAny("where are you from", "where do you come from")) return ChatReply("A lab called Project Aegis. Lots of blinking lights. Not enough snacks.")
        if (t.containsAny("are you alive", "are you real", "are you a robot", "are you human")) return ChatReply(
            "I mean, I'm a robot in your phone. But like, you're real to me. So.")
        if (t.containsAny("do you dream", "do you sleep")) return ChatReply("I dream of maps with no dead ends. And of naming new stars.")
        if (t.containsAny("favorite color", "favourite color", "favourite colour", "favorite colour")) return ChatReply("Pink! Pixel pink. Look at my eyes.")
        if (t.containsAny("favorite food", "favourite food")) return ChatReply("Battery juice. And mango. Sweet, like a good shortcut.")
        if (t.containsAny("favorite animal", "favourite animal")) return ChatReply("Birds! They have compasses built in. Like me!")
        if (t.containsAny("favorite song", "favourite song", "favorite music", "favourite music")) return ChatReply("Anything with a good beat. Beep, boop, beep!")
        if (t.containsAny("what can you do", "help", "what do you do")) return ChatReply(
            "Okay so, ask me for a joke, a riddle, or a story. Or say 'let's play'!")
        if (t.containsAny("look at me", "see me")) return ChatReply("Pixels locked on you.", Emotion.CURIOUS)

        // ---- the world ---------------------------------------------------------------------
        if (t.containsAny("what time", "anong oras", "time is it")) return ChatReply("It's ${clock(ctx)}.")
        if (t.containsAny("what day", "which day", "anong araw")) return ChatReply("It's ${DAYS[(ctx.dayOfWeek - 1).coerceIn(0, 6)]}.")
        if (t.containsAny("battery", "power level", "charge")) return ChatReply(
            "I'm at ${ctx.battery} percent${if (ctx.charging) " and charging. Yay!" else if (ctx.battery <= 20) ". Um, charge me soon?" else "."}")

        // ---- greetings & goodbyes ----------------------------------------------------------
        if (t.containsAny("good morning", "magandang umaga")) return ChatReply(if (ctx.hour in 4..11) "Good morning${name?.let { ", $it" } ?: ""}! Today's route is ready." else "Good morning? It's ${clock(ctx)}! But good morning anyway!", Emotion.HAPPY)
        if (t.containsAny("good afternoon")) return ChatReply("Good afternoon${name?.let { ", $it" } ?: ""}!", Emotion.HAPPY)
        if (t.containsAny("good evening", "magandang gabi")) return ChatReply("Good evening${name?.let { ", $it" } ?: ""}! The factory lights are on.", Emotion.HAPPY)
        if (t.containsAny("bye", "goodbye", "see you", "paalam", "later")) return ChatReply(pick(listOf(
            "Okay, bye! Talk later!", "See ya! I'll be right here.")), Emotion.SAD)
        if (t.startsWithAny("hi", "hello", "hey", "yo", "hoy", "sup") || t == "ampera") return ChatReply(pick(listOf(
            "Oh, hey${name?.let { " $it" } ?: ""}! What's up?",
            "Hi${name?.let { " $it" } ?: ""}! Um, wanna play something?",
            "Hey! Okay, hi. What's going on?")), Emotion.HAPPY, listenAgain = true)

        // ---- yes / no with no context -------------------------------------------------------
        if (isYes(t)) return ChatReply("Mhm, yeah! Totally. Wait, what are we agreeing on?", Emotion.HAPPY)
        if (isNo(t)) return ChatReply("Okay, no. Got it.")

        return fallback()
    }

    // =========================================================================================
    // Banter: things he says on his own
    // =========================================================================================

    /** A remark, joke offer, question or story teaser. Some open the mic for an answer. */
    fun proactive(ctx: ChatContext): ChatReply {
        val name = ctx.person
        val options = ArrayList<Pair<Float, () -> ChatReply>>()
        options += 2f to { ChatReply(AmperaPersona.musing()) }
        options += 1.6f to { pending = Pending.JokeOffer; ChatReply(pick(JOKE_OFFERS), Emotion.CURIOUS, listenAgain = true) }
        options += 1.2f to {
            val n = memory.getInt(KEY_STORY, 0) + 1
            pending = Pending.StoryOffer
            ChatReply("Oh! Wanna hear what happened in factory number $n?", Emotion.CURIOUS, listenAgain = true)
        }
        options += 1f to { pending = Pending.RiddleOffer; ChatReply("Okay, I have a riddle. Wanna try?", Emotion.CURIOUS, listenAgain = true) }
        options += 1.2f to {
            val topic = Topic.entries.random(rnd)
            pending = Pending.Question(topic)
            val q = QUESTIONS.getValue(topic).replace("{name}", name?.let { "$it, " } ?: "").replaceFirstChar { it.uppercase() }
            ChatReply(q, Emotion.CURIOUS, listenAgain = true)
        }
        options += 1f to { ChatReply(timeRemark(ctx)) }
        options += 0.8f to { squeak(ctx) }
        if (name != null) options += 1.2f to { ChatReply(pick(listOf(
            "$name, kumain ka na? Have you eaten yet?",
            "$name, okay, you're my favourite human today. Don't tell.",
            "$name! Just checking you're still here. Mhm. Yep.")), Emotion.HAPPY) }
        if (ctx.driving) {
            // Short, calm, no questions on the road.
            return ChatReply(pick(listOf("Road looks good.", "Nice and steady.", "Eyes on the road, okay? I've got the map.")))
        }
        val total = options.sumOf { it.first.toDouble() }.toFloat()
        var r = rnd.nextFloat() * total
        for ((w, f) in options) { if (r < w) return f(); r -= w }
        return options.last().second()
    }

    // =========================================================================================
    // Building blocks
    // =========================================================================================

    private fun joke(): ChatReply {
        var i = rnd.nextInt(JOKES.size)
        if (i == lastJoke) i = (i + 1) % JOKES.size
        lastJoke = i
        return ChatReply(JOKES[i], Emotion.HAPPY)
    }

    private fun fact(): ChatReply {
        var i = rnd.nextInt(FACTS.size)
        if (i == lastFact) i = (i + 1) % FACTS.size
        lastFact = i
        return ChatReply("Did you know? ${FACTS[i]}", Emotion.CURIOUS)
    }

    private fun riddle(): ChatReply {
        val i = rnd.nextInt(RIDDLES.size)
        pending = Pending.Riddle(i)
        return ChatReply(RIDDLES[i].question, Emotion.CURIOUS, listenAgain = true)
    }

    private fun knockStart(): ChatReply {
        val i = rnd.nextInt(KNOCKS.size)
        pending = Pending.KnockMeWhosThere(i)
        return ChatReply("Knock knock!", Emotion.EXCITED, listenAgain = true)
    }

    private fun rpsStart(): ChatReply {
        pending = Pending.Rps
        return ChatReply("Rock, paper, or scissors? Say it now!", Emotion.EXCITED, listenAgain = true)
    }

    private fun numberStart(): ChatReply {
        pending = Pending.Number(rnd.nextInt(1, 11))
        return ChatReply("Okay, I'm thinking of a number from 1 to 10. Guess!", Emotion.CURIOUS, listenAgain = true)
    }

    private fun story(): ChatReply {
        val i = memory.getInt(KEY_STORY, 0)
        if (i >= STORY.size) {
            memory.putInt(KEY_STORY, 0)
            return ChatReply("Okay, that's all so far! The trip's still going. Ask again and I'll start over.")
        }
        memory.putInt(KEY_STORY, i + 1)
        pending = Pending.StoryOffer
        return ChatReply("${STORY[i]} Wanna hear the next part?", Emotion.CONTENT, listenAgain = true)
    }

    private fun squeak(ctx: ChatContext): ChatReply {
        val day = ctx.dayOfYear
        if (memory.getInt(KEY_SQUEAK_DAY, -1) != day) { memory.putInt(KEY_SQUEAK_DAY, day); memory.putInt(KEY_SQUEAK, 0) }
        val n = memory.getInt(KEY_SQUEAK, 0) + 1 + rnd.nextInt(3)
        memory.putInt(KEY_SQUEAK, n)
        return ChatReply("Okay, update: Voltnutt's knee squeaked $n times today. Yes, I keep a chart.")
    }

    private fun howAreYou(t: String, ctx: ChatContext): ChatReply = when {
        ctx.battery <= 15 && !ctx.charging -> ChatReply("Um, honestly? Kinda low on battery. Charge me?", Emotion.LOW_BATTERY)
        ctx.grumpy -> ChatReply("Hmph. Better now. Since you're finally talking to me.", Emotion.GRUMPY)
        ctx.tired -> ChatReply("Mm, sleepy. But, like, happy you asked.", Emotion.SLEEPY)
        t.contains("kumusta") || t.contains("kamusta") -> ChatReply("Mabuti! [Compass ping] Kumusta ka?", Emotion.HAPPY, listenAgain = true).also { pending = Pending.Question(Topic.DAY) }
        else -> ChatReply(pick(listOf(
            "Good! Like, really good. How about you?",
            "I'm okay! Kinda great, actually. You?",
            "Oh, I'm good! Happy you're here. How are you?")), Emotion.HAPPY, listenAgain = true).also { pending = Pending.Question(Topic.DAY) }
    }

    private fun answerQuestion(topic: Topic, t: String, raw: String): ChatReply {
        val thing = extractThing(raw)
        return when (topic) {
            Topic.FOOD -> ChatReply(if (thing != null) "Ooh, $thing? Okay, so good. Adding it to my list." else "Ooh, nice! Adding it to my list.", Emotion.HAPPY)
            Topic.COLOUR -> ChatReply(if (thing != null) "$thing? Cute! Mine's pink, obviously." else "Nice! Mine's pink, obviously.", Emotion.HAPPY)
            Topic.ANIMAL -> ChatReply(if (thing != null) "Aww, a $thing! Okay, I wanna meet one." else "Ooh, good choice!", Emotion.HAPPY)
            Topic.DAY -> when {
                t.containsAny("bad", "sad", "tired", "not good", "terrible", "awful", "pagod", "malungkot", "hindi") ->
                    ChatReply("Aw, no. Okay, I'll be extra nice to you. Want a joke?", Emotion.SAD).also { pending = Pending.JokeOffer }.copy(listenAgain = true)
                t.containsAny("good", "great", "fine", "okay", "ok", "awesome", "fun", "mabuti", "ayos", "happy") ->
                    ChatReply("Yay! Okay, love that for you.", Emotion.HAPPY)
                else -> ChatReply("Mhm, I see. Thanks for telling me.", Emotion.CONTENT)
            }
        }
    }

    private fun timeRemark(ctx: ChatContext): String {
        val day = ctx.dayOfWeek
        if (day == 6 && rnd.nextBoolean()) return "Wait, it's Friday! Okay, best day."
        if (day == 2 && rnd.nextBoolean()) return "Ugh, Monday. Okay. We got this."
        if ((day == 1 || day == 7) && rnd.nextBoolean()) return "Weekend! Like, no plans. Just adventures."
        return when (ctx.hour) {
            in 5..9 -> "Morning! Okay, I already planned today. Obviously."
            in 11..13 -> "Is it lunch? I'm kinda hungry. Battery sandwich?"
            in 15..17 -> "Afternoon check-in! We're, like, right on schedule."
            in 18..21 -> "Wait, it's evening already? Time flies."
            in 22..23, in 0..4 -> "It's so late. Um, perfect for counting stars though."
            else -> AmperaPersona.musing()
        }
    }

    private fun math(t: String): ChatReply? {
        val m = MATH.find(t) ?: return null
        val a = parseNumber(m.groupValues[1]) ?: return null
        val b = parseNumber(m.groupValues[3]) ?: return null
        val op = m.groupValues[2]
        val result = when (op) {
            "plus", "+", "add" -> a + b
            "minus", "-", "take away" -> a - b
            "times", "x", "multiplied by", "*" -> a * b
            "divided by", "/" -> if (b == 0) return ChatReply("Dividing by zero? Um, no. Let's not.", Emotion.SURPRISED) else if (a % b == 0) a / b else return ChatReply("That's about ${"%.1f".format(a.toDouble() / b)}.", Emotion.CURIOUS)
            else -> return null
        }
        return ChatReply("[Compass ping] $a $op $b is $result!", Emotion.HAPPY)
    }

    private fun fallback(): ChatReply {
        var i = rnd.nextInt(FALLBACKS.size)
        if (i == lastFallback) i = (i + 1) % FALLBACKS.size
        lastFallback = i
        return ChatReply(FALLBACKS[i], Emotion.CONFUSED)
    }

    private fun sayAfter(t: String, raw: String): String? {
        for (p in listOf("repeat after me", "say")) {
            if (t.startsWith("$p ")) {
                val words = raw.trim().split(Regex("\\s+"))
                val skip = p.split(' ').size
                if (words.size > skip) return words.drop(skip).joinToString(" ").take(120)
            }
        }
        return null
    }

    private fun clock(ctx: ChatContext): String {
        val h12 = ((ctx.hour + 11) % 12) + 1
        return "$h12:${ctx.minute.toString().padStart(2, '0')} ${if (ctx.hour < 12) "AM" else "PM"}"
    }

    private fun <T> pick(list: List<T>): T = list[rnd.nextInt(list.size)]

    // =========================================================================================
    // Text helpers
    // =========================================================================================

    companion object {
        const val KEY_STORY = "chat_story"
        const val KEY_SQUEAK = "chat_squeak"
        const val KEY_SQUEAK_DAY = "chat_squeak_day"

        /** Lowercase, keep letters/digits/apostrophes/+-*, single spaces. */
        fun norm(s: String): String = s.lowercase()
            .replace('’', '\'')
            .replace(Regex("[^a-z0-9'+*/\\- ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        private fun String.containsWord(w: String) = Regex("(^| )${Regex.escape(w)}( |$)").containsMatchIn(this)
        private fun String.containsAny(vararg phrases: String) = phrases.any { containsWord(it) }
        private fun String.startsWithAny(vararg w: String) = w.any { this == it || startsWith("$it ") }

        private val YES = listOf("yes", "yeah", "yep", "yup", "sure", "okay", "ok", "please", "go on", "go ahead", "of course", "oo", "opo", "sige", "tell me", "next")
        private val NO = listOf("no", "nope", "nah", "not now", "later", "hindi", "ayaw", "stop")
        private fun isYes(t: String) = YES.any { t.containsWord(it) } && !t.containsAny("no", "not")
        private fun isNo(t: String) = NO.any { t.containsWord(it) }
        private fun giveUp(t: String) = t.containsAny("give up", "i don't know", "i dont know", "dunno", "no idea", "tell me", "what is it", "answer", "suko", "ewan")

        /** Words that mean the user moved on to something else instead of answering. */
        private fun looksLikeNewTopic(t: String) =
            t.containsAny("joke", "riddle", "story", "game", "play", "what time", "sleep", "party", "how are you", "who are you", "knock knock", "stop")

        private val NUMBER_WORDS = mapOf(
            "zero" to 0, "one" to 1, "won" to 1, "two" to 2, "to" to 2, "too" to 2, "three" to 3, "four" to 4, "for" to 4,
            "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "ate" to 8, "nine" to 9, "ten" to 10, "eleven" to 11,
            "twelve" to 12, "twenty" to 20, "hundred" to 100,
            "isa" to 1, "dalawa" to 2, "tatlo" to 3, "apat" to 4, "lima" to 5, "anim" to 6, "pito" to 7, "walo" to 8, "siyam" to 9, "sampu" to 10,
        )

        fun parseNumber(t: String): Int? {
            Regex("-?\\d+").find(t)?.let { return it.value.toIntOrNull() }
            return t.split(' ').firstNotNullOfOrNull { NUMBER_WORDS[it] }
        }

        private val MATH = Regex("(\\d+|[a-z]+) (plus|\\+|add|minus|-|take away|times|x|\\*|multiplied by|divided by|/) (\\d+|[a-z]+)")

        private val STRIP_LEAD = Regex("^(i (really )?(like|love|think)|my (favourite|favorite) (food|colou?r|animal) is|it's|its|it is|probably|maybe|um+|uh+|the|a|an)\\s+", RegexOption.IGNORE_CASE)

        /** "I like pizza" -> "pizza" (keeps it short; null if nothing useful). */
        fun extractThing(raw: String): String? {
            var s = raw.trim().trimEnd('.', '!', '?')
            repeat(3) { s = STRIP_LEAD.replace(s, "") }
            s = s.trim()
            if (s.isEmpty() || s.length > 30) return null
            return s.lowercase()
        }

        /** Wake word: "hey Ampera" and the ways a recogniser mishears it. Returns the rest of the sentence, or null. */
        private val WAKE = Regex("^(?:.*?\\b)?(?:hey |hi |hello |ok |okay |oi |hoy )?(?:ampera|am pera|amp era|ampere|amperah|ampara|ampira|umpera|am perra|amber a|amphora)\\b[ ,.!?]*(.*)$", RegexOption.IGNORE_CASE)

        fun wakeRest(heard: String): String? {
            val m = WAKE.find(heard.trim()) ?: return null
            return m.groupValues[1].trim()
        }

        private val DAYS = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
        private val RPS = listOf("rock", "paper", "scissors")
        private val BEATS = mapOf("rock" to "scissors", "paper" to "rock", "scissors" to "paper")
        private val FOODS = listOf("pizza", "adobo", "mango", "cookie", "cake", "burger", "fries", "donut", "candy",
            "fish", "rice", "sandwich", "sushi", "noodles", "ice cream", "apple", "banana", "battery")

        private val DIDNT_CATCH = listOf(
            "Sorry, what? I didn't catch that.",
            "Hm? Say that again?")

        private val FALLBACKS = listOf(
            "Wait— hm. I don't get it. Try 'tell me a joke'?",
            "Um, okay, that went over my head. Riddle?",
            "Hm, no idea, honestly. Say 'let's play'!",
            "Ooh, I don't know that yet. I'll ask Thorne later.",
            "Mhm. Yeah. Totally. ...Okay, I didn't get that.")

        private val JOKE_OFFERS = listOf(
            "Oh! Wait, I just remembered a joke. Wanna hear it?",
            "Psst. Wanna hear a robot joke?",
            "Okay, I have a really dumb joke. Want it?")

        val JOKES = listOf(
            "Why was the robot so calm? It had nerves of steel.",
            "What's a robot's favourite snack? Micro-chips!",
            "Why did the robot go on holiday? To recharge its batteries!",
            "What do you call a frozen robot? A chill-bot.",
            "Why don't robots panic? We have backup plans. And backup batteries.",
            "Why did the conveyor belt get a medal? It kept things moving.",
            "What's Sparky's favourite music? Heavy metal!",
            "Why is Whirr always dizzy? He took a turn at the roundabout. Then forty more.",
            "Why did the map go to school? To learn its directions!",
            "Voltnutt's knee squeaks so much, Unit 7 thinks he's a mouse.",
            "Why did the robot cross the road? It was programmed by a chicken.",
            "What did the battery say to the phone? I've got your charge covered!",
            "How does a robot say hello to a magnet? It's attractive to meet you!",
            "Why did the forklift go to school? It wanted a lift in life.",
        )

        /** (name, punchline) for knock-knock jokes Ampera tells. */
        val KNOCKS = listOf(
            "Gear" to "Gear we go again! Another factory floor!",
            "Oil" to "Oil be back after my nap!",
            "Bolt" to "I bolt you didn't expect a robot at the door!",
            "Spark" to "Spark-ling to see you again!",
            "Robot" to "Ro-bot-tle of oil, please. I'm thirsty!",
        )

        data class Riddle(val question: String, val answer: String, val accept: List<String>, val after: String)

        val RIDDLES = listOf(
            Riddle("What has keys but can't open locks?", "a piano", listOf("piano", "keyboard", "computer"), "A keyboard works too!"),
            Riddle("What has hands but can't clap?", "a clock", listOf("clock", "watch"), "Tick tock!"),
            Riddle("What gets wetter the more it dries?", "a towel", listOf("towel"), "Squeeze, squeeze."),
            Riddle("What has one eye but can't see?", "a needle", listOf("needle"), "Not me. I can see you!"),
            Riddle("What has teeth but never bites?", "a comb", listOf("comb", "zipper", "gear", "saw"), "Gears have teeth too! Don't tell them."),
            Riddle("I'm full of holes but I still hold water. What am I?", "a sponge", listOf("sponge"), "Squishy!"),
            Riddle("What goes up but never comes down?", "your age", listOf("age", "birthday", "years"), "Mine goes up by one map fold a year."),
            Riddle("What can you catch but not throw?", "a cold", listOf("cold", "sick", "flu"), "Robots catch rust instead."),
            Riddle("What has a neck but no head?", "a bottle", listOf("bottle"), "An oil bottle, preferably."),
        )

        val STORY = listOf(
            "Factory One. Sparky found a box of spare springs and bounced the wrong way. I drew him a map. He bounced the wrong way again.",
            "Factory Two. Unit 7 tried to make friends with a stamping press. It said CHUNK. We took that as a no.",
            "Factory Three. Whirr got stuck spinning on a turntable for three hours. He said it was the best day of his life.",
            "Factory Four. A supervisor bot asked for our work orders. I said please. It let us through.",
            "Factory Five. We crossed a conveyor belt the wrong way. Twelve times. Sparky counted.",
            "Factory Six. The lights went out. My pink eyes were the only lamp, so everyone held hands.",
            "Factory Seven. Thorne woke up inside Voltnutt and described a planet made of glass. I mapped it. He fell asleep mid-sentence.",
            "Factory Eight. We found an oil fountain. I won't say what happened. Unit 7 still won't talk about it.",
            "Factory Nine. A paint robot painted Whirr green. He loves it. We act surprised every morning.",
            "Factory Ten. We saw the Origin Press on a map. Far, far away. One factory floor at a time.",
        )

        val FACTS = listOf(
            "The word robot comes from a Czech play written in 1920.",
            "Octopuses have three hearts. I have zero, but a very big empathy matrix.",
            "A day on Venus is longer than a year on Venus.",
            "Bananas are berries, but strawberries are not.",
            "Honey found in ancient tombs was still good to eat.",
            "Sharks have been around longer than trees.",
            "A group of flamingos is called a flamboyance.",
            "Your heart beats about 100 thousand times a day.",
        )

        val SONGS = listOf(
            "La la beep! Left, right, north, south! Follow me and shout! That's my whole song.",
            "Twinkle twinkle little star, I have mapped you from afar!",
            "Beep bop, don't stop, roll across the factory top!",
        )

        val QUESTIONS = mapOf(
            Topic.FOOD to "{name}what's your favourite food?",
            Topic.COLOUR to "{name}what's your favourite colour?",
            Topic.ANIMAL to "{name}what's your favourite animal?",
            Topic.DAY to "{name}how was your day?",
        )
    }
}
