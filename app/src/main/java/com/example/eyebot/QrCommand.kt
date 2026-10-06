package com.example.eyebot

/**
 * Text commands EyeBot understands. They arrive from QR cards (front camera), and the same
 * parser handles text lines sent by the pan-tilt microcontroller.
 *
 * Accepted payloads (case-insensitive; spaces/dashes == underscores; an optional "EYEBOT:" or
 * "eyebot://" prefix is ignored):
 *
 *   HAPPY, CURIOUS, SLEEPY, EXCITED, ...   any Emotion name or alias  -> SetEmotion
 *   EMOTION:HAPPY                          explicit form              -> SetEmotion
 *   FEED_ME                                start the Feed-Me game     -> FeedMe
 *   FOOD, FOOD:PIZZA, APPLE, COOKIE, ...   feed it                    -> Food
 *   SLEEP, GOODNIGHT                       dark low-brightness sleep  -> Sleep
 *   WAKE, WAKE_UP, GOOD_MORNING            wake back up               -> Wake
 *   PARTY, PARTY_MODE                      party mode                 -> Party
 *   COLOR:RED, COLOR:#00FFAA, COLOR:RESET  set the base eye colour    -> SetColor
 *   SAY:Hello there                        speak the text             -> Say
 *
 * Pure Kotlin (no Android) so it is unit-tested on the JVM.
 */
sealed interface QrCommand {
    data class SetEmotion(val emotion: Emotion) : QrCommand
    data object FeedMe : QrCommand
    data class Food(val item: String) : QrCommand
    data object Sleep : QrCommand
    data object Wake : QrCommand
    data object Party : QrCommand
    /** [argb] null means "back to the default teal". */
    data class SetColor(val argb: Int?, val label: String) : QrCommand
    data class Say(val text: String) : QrCommand

    companion object {
        private val FOODS = setOf(
            "APPLE", "BANANA", "PIZZA", "COOKIE", "CAKE", "BURGER", "FRIES", "DONUT", "CANDY",
            "FISH", "RICE", "ADOBO", "MANGO", "SANDWICH", "SUSHI", "NOODLES", "ICE_CREAM", "BATTERY",
        )
        private val FEED_WORDS = setOf("FEED_ME", "FEEDME", "FEED", "HUNGRY_GAME", "IM_HUNGRY")
        private val SLEEP_WORDS = setOf("SLEEP", "GO_TO_SLEEP", "GOODNIGHT", "GOOD_NIGHT", "NIGHT", "SLEEP_MODE")
        private val WAKE_WORDS = setOf("WAKE", "WAKE_UP", "WAKEUP", "GOOD_MORNING", "MORNING", "AWAKE")
        private val PARTY_WORDS = setOf("PARTY", "PARTY_MODE", "PARTYMODE", "DANCE", "DISCO")

        val NAMED_COLORS: Map<String, Int> = mapOf(
            "RED" to 0xFFFF3B3B.toInt(),
            "ORANGE" to 0xFFFF9A2E.toInt(),
            "YELLOW" to 0xFFFFE03B.toInt(),
            "GREEN" to 0xFF3BFF6A.toInt(),
            "CYAN" to 0xFF3FE8F0.toInt(),
            "TEAL" to 0xFF3FE0C5.toInt(),
            "BLUE" to 0xFF3B8BFF.toInt(),
            "PURPLE" to 0xFFA45BFF.toInt(),
            "MAGENTA" to 0xFFFF4FD8.toInt(),
            "PINK" to 0xFFFF7FB8.toInt(),
            "WHITE" to 0xFFFFFFFF.toInt(),
        )
        private val RESET_WORDS = setOf("RESET", "DEFAULT", "NORMAL", "NONE", "OFF")

        fun parse(raw: String?): QrCommand? {
            if (raw == null) return null
            var text = raw.trim()
            if (text.isEmpty()) return null
            text = text.removePrefixIgnoreCase("eyebot://").removePrefixIgnoreCase("eyebot:").trim()

            // Commands with an argument: KEY:VALUE (also KEY=VALUE).
            val sep = text.indexOfFirst { it == ':' || it == '=' }
            if (sep > 0) {
                val key = norm(text.substring(0, sep))
                val value = text.substring(sep + 1).trim()
                when (key) {
                    "SAY", "SPEAK", "TALK" -> return if (value.isNotEmpty()) Say(value.take(MAX_SAY)) else null
                    "EMOTION", "EMO", "FACE", "MOOD" -> return Emotion.fromName(value)?.let { SetEmotion(it) }
                    "FOOD", "EAT", "SNACK" -> return Food(norm(value).ifEmpty { "FOOD" }.lowercase())
                    "COLOR", "COLOUR", "EYE", "EYES" -> return parseColor(value)
                    "MODE", "GAME" -> return parse(value)
                }
                // Unknown key: fall through and try the whole text as a word.
            }

            val word = norm(text)
            return when {
                word in FEED_WORDS -> FeedMe
                word == "FOOD" || word in FOODS -> Food(word.lowercase())
                word in SLEEP_WORDS -> Sleep
                word in WAKE_WORDS -> Wake
                word in PARTY_WORDS -> Party
                else -> Emotion.fromName(word)?.let { SetEmotion(it) }
            }
        }

        private fun parseColor(value: String): QrCommand? {
            val v = value.trim()
            val key = norm(v)
            if (key in RESET_WORDS) return SetColor(null, "default")
            NAMED_COLORS[key]?.let { return SetColor(it, key.lowercase()) }
            val hex = v.removePrefix("#")
            if (hex.length == 6 && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                return SetColor((0xFF000000L or hex.toLong(16)).toInt(), "#" + hex.uppercase())
            }
            return null
        }

        private fun norm(s: String) = s.trim().uppercase().replace(' ', '_').replace('-', '_')

        private fun String.removePrefixIgnoreCase(p: String) =
            if (startsWith(p, ignoreCase = true)) substring(p.length) else this

        private const val MAX_SAY = 120
    }
}
