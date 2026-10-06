package com.example.eyebot

/**
 * Little pixel-art icons drawn in the eye colour (pure Kotlin, unit-tested).
 * Each icon is a 12 x 12 grid: '#' = lit pixel, '.' = off.
 */
enum class PixelIcon(vararg rows: String) {
    DOG(
        "............",
        ".##......##.",
        "###.####.###",
        "###########.",
        ".##########.",
        ".##.####.##.",
        ".##########.",
        "..########..",
        "..###..###..",
        "...######...",
        "....####....",
        "............",
    ),
    CAT(
        "#..........#",
        "##........##",
        "###......###",
        "############",
        "############",
        "##.##..##.##",
        "############",
        "#####..#####",
        ".####..####.",
        "..########..",
        "...######...",
        "............",
    ),
    BIRD(
        "............",
        "....####....",
        "...######...",
        "..###.####..",
        "..########.#",
        "#########.##",
        ".#########..",
        "..########..",
        "...######...",
        ".....#.#....",
        "....##.##...",
        "............",
    ),
    COW(
        "#..........#",
        "##.######.##",
        ".##########.",
        "..########..",
        "..#.####.#..",
        "..########..",
        "...######...",
        "..########..",
        "..#.####.#..",
        "..########..",
        "...######...",
        "............",
    ),
    SHEEP(
        "..##.##.##..",
        ".##########.",
        "############",
        "##.######.##",
        "...######...",
        "...#.##.#...",
        "...######...",
        "....####....",
        "....####....",
        "############",
        ".##########.",
        "..##.##.##..",
    ),
    HORSE(
        "......##....",
        ".....###....",
        "....######..",
        "...########.",
        "..####.####.",
        ".#########..",
        "#########...",
        "########....",
        ".######.....",
        "..#####.....",
        "..####......",
        "............",
    ),
    BEAR(
        ".##......##.",
        "####....####",
        "############",
        ".##########.",
        ".##.####.##.",
        ".##########.",
        ".####..####.",
        "..###..###..",
        "..########..",
        "...######...",
        "............",
        "............",
    ),
    TEDDY(
        ".##......##.",
        "####.##.####",
        "############",
        ".##.####.##.",
        ".##########.",
        "..###..###..",
        "..########..",
        ".##########.",
        "###.####.###",
        "############",
        ".####..####.",
        ".###....###.",
    ),
    PAW(
        "............",
        "..##....##..",
        "..##....##..",
        "##..####..##",
        "##.######.##",
        "...######...",
        "..########..",
        "..########..",
        "...######...",
        "....####....",
        "............",
        "............",
    ),
    BOLT(
        "......####..",
        ".....####...",
        "....####....",
        "...####.....",
        "..#########.",
        ".#########..",
        "......###...",
        ".....###....",
        "....###.....",
        "...##.......",
        "..##........",
        ".#..........",
    );

    val grid: List<String> = rows.toList()

    fun lit(x: Int, y: Int): Boolean = grid.getOrNull(y)?.getOrNull(x) == '#'

    companion object {
        const val SIZE = 12

        /** COCO label from the object detector -> icon (null = not an animal we react to). */
        fun forLabel(label: String): PixelIcon? = when (label.lowercase()) {
            "dog" -> DOG
            "cat" -> CAT
            "bird" -> BIRD
            "cow" -> COW
            "sheep" -> SHEEP
            "horse" -> HORSE
            "bear" -> BEAR
            "teddy bear" -> TEDDY
            "elephant", "zebra", "giraffe" -> PAW
            else -> null
        }
    }
}

/** What EyeBot does when it spots each kind of animal. */
object AnimalReactions {
    data class Reaction(val icon: PixelIcon, val sfx: Sfx, val emotion: Emotion, val phrase: String, val isPet: Boolean)

    fun forLabel(label: String): Reaction? {
        val icon = PixelIcon.forLabel(label) ?: return null
        return when (icon) {
            PixelIcon.DOG -> Reaction(icon, Sfx.DOG, Emotion.EXCITED, "A doggy!", true)
            PixelIcon.CAT -> Reaction(icon, Sfx.CAT, Emotion.LOVESTRUCK, "Hello, kitty!", true)
            PixelIcon.BIRD -> Reaction(icon, Sfx.BIRD, Emotion.CURIOUS, "Tweet tweet!", true)
            PixelIcon.TEDDY -> Reaction(icon, Sfx.TOY, Emotion.CONTENT, "A teddy bear!", false)
            PixelIcon.COW, PixelIcon.SHEEP, PixelIcon.HORSE -> Reaction(icon, Sfx.FARM, Emotion.SURPRISED, "Whoa, a ${label.lowercase()}!", true)
            PixelIcon.BEAR -> Reaction(icon, Sfx.GASP, Emotion.SURPRISED, "Is that a bear?!", true)
            else -> Reaction(icon, Sfx.CURIOUS, Emotion.CURIOUS, "What animal is that?", true)
        }
    }
}
