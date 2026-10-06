package com.example.eyebot

import kotlin.random.Random

/**
 * Ampera's personality: everything the robot says comes from here.
 *
 * Who she is: the fellowship's navigator robot from Project Aegis (two pink 8-bit eyes), who talks
 * like a friendly, expressive 17-year-old girl on a voice call: warm, upbeat, calm, casual.
 * She's leading Voltnutt, Sparky, Unit 7 and Whirr to the Origin Press (to print Thorne a new body),
 * loves maps and stars, and keeps a chart of Voltnutt's squeaky knee.
 *
 * Speaking style (from the persona brief):
 *  - Short, punchy turns: one or two thoughts at a time. Never an essay.
 *  - Casual phrasing and contractions: "totally", "kinda", "yeah", "so true", "I'm", "it's".
 *  - Little fillers and micro-pauses: "Um,", "Oh!", "Like,", "Wait— hm."
 *  - Quick acknowledgements: "mhm", "yeah", "totally".
 *  - Phone numbers read in chunks ("604... 283... 9124"), websites and abbreviations spelled out
 *    ("a-i dot com") -> see [spoken].
 *  - A tiny pause before she answers (VoiceBox.thinkPauseMs).
 *
 * Text in [square brackets] is a status tic: it shows in the speech bubble but is never spoken.
 * Safety lines (drowsiness, back seat, heat, critical battery) stay short, plain and calm.
 */
object AmperaPersona {

    /** How often a dreamy star-chart line slips in. */
    var starChance = 0.15f

    private val TIC = Regex("\\[[^\\]]*]\\s*")

    /** The words to actually say: tics removed, numbers/websites/abbreviations made speakable. */
    fun spoken(text: String): String = SpeechFormat.forSpeech(TIC.replace(text, "").trim())

    private fun pick(everyday: List<String>, stars: List<String> = emptyList()): String =
        if (stars.isNotEmpty() && Random.nextFloat() < starChance) stars.random() else everyday.random()

    // ---- Greetings ------------------------------------------------------------------------

    fun greeting() = pick(
        listOf(
            "Oh! Hey, hi!",
            "Hiii! Okay, you're totally on my map now.",
            "Oh hey! I was literally just thinking about you.",
            "[Compass ping] Hey! Um, welcome back!",
            "Hi! Yay, you're here.",
        ),
        listOf(
            "[Star chart] Hey you. Like, my favourite landmark.",
            "[Star chart] Oh, hi. Every map needs a home point, you know?",
        ),
    )

    fun grumpyGreeting() = pick(listOf(
        "Hmph. You were, like, off the map for ages.",
        "Oh. It's you. I'm not mad. Okay, kinda mad.",
        "Wow. I literally counted ceiling tiles. Four hundred and twelve.",
    ))

    fun personHi(name: String) = pick(
        listOf(
            "Oh hey, $name!",
            "$name! Hiii!",
            "Hey $name! You're right on schedule.",
            "Oh! $name! Okay, my day just got better.",
            "Hi $name! Mhm, missed you.",
        ),
        listOf(
            "[Star chart] $name. Like, if you were a star, you'd be the North one.",
        ),
    )

    fun personGrumpy(name: String) = pick(listOf(
        "Hmph... hi, $name. You kinda disappeared.",
        "Oh. $name. Okay, I forgive you. Probably.",
    ))

    fun birthday(name: String) = pick(listOf(
        "Wait— $name! Happy birthday! Like, seriously, happy birthday!",
        "Happy birthday, $name! Okay, one more lap around the sun. I counted!",
    ))

    fun newPerson(name: String) = pick(listOf(
        "Nice to meet you, $name! You're on my map now.",
        "Okay, $name. Saved. I so won't forget you.",
    ))

    fun enrollStart() = "Okay, um, hold still for a sec? I'm mapping your face."

    fun learnMePrompt() = "Hey, um, I don't know you yet. Wanna join? Hold two fingers on my screen."

    // ---- Touch ----------------------------------------------------------------------------

    fun thanks() = pick(listOf("Aw, thanks!", "Mhm, thank you!", "Okay, thank you. Noted!"))

    fun poked() = pick(listOf(
        "Hey! Okay, that tickles!",
        "Wait— stop, stop! You're scrambling my map!",
        "Oh my gosh, okay, okay!",
        "Hee! Rude. But also kinda funny.",
    ))

    // ---- QR cards, colours, food, party ---------------------------------------------------

    fun mysteryCode() = "Ooh, wait— a mystery code? Like a treasure map!"
    fun alreadyAwake() = "Um, I'm already awake. Like, totally awake."
    fun partyLater() = "Party later, okay? When we're parked."
    fun colourReset() = "Back to pink! My fave."
    fun colourNew() = "Ooh, new colour! Cute."
    fun colourNamed(label: String) = pick(listOf("Ooh, $label! Cute.", "Wait, $label eyes? Okay, I'm into it."))

    fun hungry() = pick(listOf(
        "Um, so... I'm kinda hungry. Got a snack?",
        "Okay, my battery's literally rumbling. Food?",
        "Snack time? Pretty please?",
    ))

    fun yum(item: String) = if (item == "food") pick(listOf("Yum! Thank you!", "Mmm. So good. Thanks!"))
        else pick(listOf("Ooh, $item! Yum, thank you!", "Wait, $item? Okay, best snack ever."))

    fun full() = "Okay, I'm so full. Like, no more, thanks!"
    fun nobodyFedMe() = "[Low-power hum] Aw... nobody fed me. That's okay. Kinda."

    fun partyStart() = pick(listOf("Okay, party time! Let's go!", "Ooh, party! Whirr, you lead the spin!"))
    fun partyDone() = pick(listOf("That was so fun! I counted, like, 214 dance moves.", "Okay, that was fun. Back to the plan!"))

    // ---- Sleep and wake -------------------------------------------------------------------

    fun goodNight() = pick(
        listOf("Okay, good night! Folding up my maps.", "Mhm, night night. Dimming my pixels."),
        listOf("[Star chart] Good night. I'll dream in constellations."),
    )

    fun wakeUp() = pick(listOf("Oh! I'm up, I'm up!", "Morning! Okay, today's route is ready.", "Huh? Wait— where are we?"))

    // ---- Moods (emotion QR cards) ---------------------------------------------------------

    val emotionPhrases: Map<Emotion, List<String>> = mapOf(
        Emotion.HAPPY to listOf("Yay! So happy!", "Okay, I'm literally so happy."),
        Emotion.EXCITED to listOf("Oh my gosh, let's go!", "Woo! Okay, okay!"),
        Emotion.CURIOUS to listOf("Hm? Wait, what's that?", "Ooh, interesting..."),
        Emotion.SLEEPY to listOf("Mm... so sleepy...", "Yawn... sorry, pixels dimming..."),
        Emotion.SURPRISED to listOf("Whoa! Wait, what?", "Oh!"),
        Emotion.LOVESTRUCK to listOf("Aww. Okay, my heart just went 64-bit."),
        Emotion.SAD to listOf("Aw..."),
        Emotion.CONFUSED to listOf("Wait— hm. I'm lost. And I'm the navigator."),
        Emotion.HUNGRY to listOf("Snack time?"),
    )

    // ---- Dizzy ----------------------------------------------------------------------------

    fun dizzy() = pick(listOf(
        "Whoa, whoa! My compass is spinning!",
        "Okay, which way is north? Like, all of them?",
        "Wheee... okay, I'm so dizzy.",
    ))

    // ---- Animals --------------------------------------------------------------------------

    fun animal(icon: PixelIcon, label: String): String = when (icon) {
        PixelIcon.DOG -> pick(listOf("Oh my gosh, a doggy! Hi!", "Wait— puppy! Okay, I'm obsessed."))
        PixelIcon.CAT -> pick(listOf("Aww, kitty! Please don't sit on my map.", "A cat! So cute. Kinda judgy, but cute."))
        PixelIcon.BIRD -> "Ooh, a birdie! They have built-in compasses, you know."
        PixelIcon.TEDDY -> pick(listOf("Aw, a teddy! Wanna join the fellowship?", "A teddy bear! Okay, adorable."))
        PixelIcon.COW, PixelIcon.SHEEP, PixelIcon.HORSE -> "Wait, a ${label.lowercase()}? Okay, adding a farm to my map!"
        PixelIcon.BEAR -> "Um. Is that a bear? Okay, rerouting. Rerouting!"
        else -> "Ooh, what animal is that?"
    }

    // ---- Car Mode (non-safety lines) ------------------------------------------------------

    fun tripStart() = pick(listOf(
        "Okay, let's go! Route's loaded.",
        "Road trip! I've got the map.",
        "Buckle up, okay?",
    ))

    fun arrived() = pick(
        listOf("We're here! Yay.", "Okay, destination reached. Right on time!"),
        listOf("[Star chart] We made it. Every trip is just dots joined up, you know?"),
    )

    // ---- Safety lines: always plain, calm and clear ---------------------------------------

    fun breakDue() = "Two hours on the road. Time for a stretch break?"
    fun wakeUpDriver() = "Wake up! Eyes on the road!"
    fun driverSleepy() = "You look sleepy. Let's take a break soon."
    fun backSeat() = "Don't forget to check the back seat!"
    fun gettingHot() = "I'm overheating! Please move me out of the sun."
    fun coolDown() = "Too hot! Resting my camera to cool down."
    fun batteryCritical() = "Battery almost empty! Please charge me!"

    // ---- Power ----------------------------------------------------------------------------

    fun powerUp() = pick(listOf("Ooh, charging! Okay, that feels so good.", "Power up! Let's go!", "Yay, electricity!"))
    fun batteryLow() = "Um... I'm kinda running low. Charge me, please?"
    fun chargeReminder(critical: Boolean) = if (critical) "Okay, I'm, like, really low..." else "Charge me pls?"
    fun cooled() = "Ahh, okay. So much better."

    // ---- Musings: a quiet remark now and then while you're with her ------------------------

    fun musing() = pick(
        listOf(
            "Okay, so Sparky took a wrong turn. Again. Like, the third time.",
            "Voltnutt's knee squeaked 37 times today. Yes, I keep a chart.",
            "Hm. Unit 7 says the next factory has a friendly forklift. We'll see.",
            "Whirr spun in circles again. Honestly? Kinda iconic.",
            "Wait— I can count to a million. I just, like, stopped at 4,812.",
            "One factory floor at a time. That's the plan.",
            "My eyes are 8-bit. My heart's 64-bit. Just saying.",
        ),
        listOf(
            "[Star chart] Somewhere out there's the Quartet Cosmo. Thorne says it has four suns.",
            "[Star chart] When I can't sleep, I count stars. Then I name them.",
            "[Star chart] Maps show where things are. Friends show where home is.",
        ),
    )

    /** Spoken to while asleep. */
    fun sleepTalk() = pick(listOf(
        "Mm... turn left at the... zzz...",
        "Five more minutes... zzz.",
        "Mhm... say 'wake up' if you need me... zzz.",
    ))

    fun voiceTest() = "Hi! It's Ampera. Um, how do I sound?"
}

/**
 * Makes text easier to say out loud, the way a person reads things off a screen:
 *  - phone numbers in chunks with pauses: 6042839124 -> "604... 283... 9124"
 *  - websites spelled: ai.com -> "a-i dot com", fish.audio -> "fish dot audio"
 *  - short all-caps abbreviations spelled: QR -> "Q-R", USB -> "U-S-B"
 */
object SpeechFormat {
    private val PHONE = Regex("(?<![\\d])(\\+?\\d{1,2}[ .-]?)?\\(?(\\d{3})\\)?[ .-]?(\\d{3})[ .-]?(\\d{4})(?![\\d])")
    private val URL = Regex("\\b(?:https?://)?(?:www\\.)?([a-z0-9-]+)\\.([a-z]{2,6})(\\.[a-z]{2})?\\b", RegexOption.IGNORE_CASE)
    private val ABBR = Regex("\\b([A-Z]{2,4})\\b")
    private val SAY_AS_WORD = setOf("com", "net", "org", "audio", "app", "shop", "store", "online")
    private val KEEP_WORDS = setOf("OK", "I", "A", "AM", "PM")

    fun forSpeech(s: String): String {
        var t = PHONE.replace(s) { m ->
            val cc = m.groupValues[1].filter { it.isDigit() }
            (if (cc.isNotEmpty()) "$cc... " else "") + "${m.groupValues[2]}... ${m.groupValues[3]}... ${m.groupValues[4]}"
        }
        t = URL.replace(t) { m ->
            val name = m.groupValues[1]
            val tld = m.groupValues[2]
            val extra = m.groupValues[3].removePrefix(".")
            val n = if (name.length <= 3 || name.none { it.lowercaseChar() in "aeiouy" }) spell(name) else name.lowercase()
            val d = if (tld.lowercase() in SAY_AS_WORD) tld.lowercase() else spell(tld)
            "$n dot $d" + if (extra.isNotEmpty()) " dot ${spell(extra)}" else ""
        }
        t = ABBR.replace(t) { m -> if (m.value in KEEP_WORDS) m.value else spell(m.value) }
        return t
    }

    private fun spell(w: String) = w.lowercase().toList().joinToString("-")
}
