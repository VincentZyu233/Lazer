package dev.naominet.lazer

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * AMLL's per-character emphasize motion, transcribed from
 * `lyric-player/dom/animation/emphasize/index.ts`.
 *
 * AMLL animates every character of the word being sung: it scales up, lifts, leans in from the side
 * it sits on within the word, and grows a white halo. Each character starts a little after the one
 * before it, which is what reads as the word being pushed apart rather than faded in. The numbers
 * here are the upstream ones, not an approximation of how it looks.
 */

/** AMLL samples each animation over 32 frames; the same count keeps the curve identical. */
internal const val LYRIC_EMPHASIZE_FRAMES = 32

/** Where AMLL hands the easing over from the in curve to the out curve. */
private const val EmpEasingMid = 0.5f

/** The lift is a fraction of an em, and background lines throw twice as far. */
private const val EmpFloatEm = 0.05f
private const val EmpFloatDelayMillis = 400f
private const val EmpFloatDurationMultiplier = 1.4f

/** AMLL's `bezier(0.2, 0.4, 0.58, 1.0)` and `bezier(0.3, 0.0, 0.58, 1.0)`. */
private const val EmpInX1 = 0.2f
private const val EmpInY1 = 0.4f
private const val EmpOutX1 = 0.3f
private const val EmpOutY1 = 0.0f
private const val EmpSharedX2 = 0.58f
private const val EmpSharedY2 = 1.0f

/** How far a word's animation is stretched out at the shortest, matching AMLL's `Math.max(1000, …)`. */
private const val EmpMinDurationMillis = 1000f

/**
 * The shared shaping function AMLL applies to both the scale and the halo amount: short words fall
 * off a cubic cliff, long ones are tamed by a square root, and 1000ms is where the two meet.
 */
private fun emphasizeShape(value: Float): Float =
    if (value > 1f) sqrt(value) else value.pow(3f)

/** Per-word emphasis strength, shared by every character in the word. */
internal data class LyricEmphasizeStrength(
    /** The animation length of one character's glow, in milliseconds. */
    val durationMillis: Float,
    /** How far characters scale and lean apart. */
    val amount: Float,
    /** How bright and wide the halo grows. */
    val blur: Float,
)

/**
 * AMLL's `calculateEmphasizeParams`. The last word of a line is deliberately stronger, because it
 * is where a phrase lands.
 */
internal fun lyricEmphasizeStrength(wordDurationMillis: Long, isLastWord: Boolean): LyricEmphasizeStrength {
    var duration = wordDurationMillis.coerceAtLeast(EmpMinDurationMillis.toLong()).toFloat()
    var amount = emphasizeShape(duration / 2000f) * 0.6f
    var blur = emphasizeShape(duration / 3000f) * 0.5f
    if (isLastWord) {
        amount *= 1.6f
        blur *= 1.5f
        duration *= 1.2f
    }
    return LyricEmphasizeStrength(
        durationMillis = duration,
        amount = amount.coerceAtMost(1.2f),
        blur = blur.coerceAtMost(0.8f),
    )
}

/**
 * When a character's glow starts, measured from the beginning of the line like AMLL's animation
 * delays. Characters of the same word are spread across the first 40% of it.
 */
internal fun lyricEmphasizeCharStartMillis(
    wordStartMillis: Long,
    strength: LyricEmphasizeStrength,
    charIndex: Int,
    characterCount: Int,
): Float = wordStartMillis.toFloat() +
    strength.durationMillis / 2.5f / characterCount.coerceAtLeast(1) * charIndex

/** Progress through an AMLL animation timeline, where its `fill: "both"` holds the ends. */
internal fun lyricEmphasizeElapsed(
    positionMillis: Long,
    startMillis: Float,
    durationMillis: Float,
): Float = if (durationMillis <= 0f) 1f
else ((positionMillis - startMillis) / durationMillis).coerceIn(0f, 1f)

/**
 * AMLL's piecewise easing: the first half runs the in curve, the second half mirrors the out curve,
 * which is what makes a character settle back to exactly one instead of overshooting.
 */
internal fun lyricEmphasizeEasing(x: Float): Float {
    val value = x.coerceIn(0f, 1f)
    return if (value < EmpEasingMid) {
        cubicBezierEase(EmpInX1, EmpInY1, EmpSharedX2, EmpSharedY2, value / EmpEasingMid)
    } else {
        1f - cubicBezierEase(
            EmpOutX1,
            EmpOutY1,
            EmpSharedX2,
            EmpSharedY2,
            (value - EmpEasingMid) / EmpEasingMid,
        )
    }
}

/** One character's transform and halo for one frame. */
internal data class LyricCharEmphasis(
    val scale: Float,
    /** Horizontal lean in em; characters left of the word's centre go one way, right of it the other. */
    val offsetXEm: Float,
    /** Vertical lift from the glow itself, in em. */
    val offsetYEm: Float,
    /** Halo opacity and its radius in em, matching AMLL's text-shadow. */
    val glowAlpha: Float,
    val glowRadiusEm: Float,
    /** The separate float animation, which AMLL composes additively on top of the glow. */
    val floatOffsetEm: Float,
)

/**
 * The whole per-character answer: [glowProgress] drives scale, lean and halo, [floatProgress] drives
 * the sine-shaped lift that starts 400ms before the glow and runs 1.4 times as long.
 *
 * [isBackgroundLine] is AMLL's rule for its second, backing vocal sheet: it throws twice as far.
 * Lazer has one lyric sheet, so the renderer leaves it at its default.
 */
internal fun lyricCharEmphasis(
    strength: LyricEmphasizeStrength,
    glowProgress: Float,
    floatProgress: Float,
    charIndex: Int,
    characterCount: Int,
    isBackgroundLine: Boolean = false,
): LyricCharEmphasis {
    val t = lyricEmphasizeEasing(glowProgress)
    val count = characterCount.coerceAtLeast(1)
    val wave = sin(floatProgress.coerceIn(0f, 1f) * PI.toFloat())
    return LyricCharEmphasis(
        scale = 1f + t * 0.1f * strength.amount,
        offsetXEm = -t * 0.03f * strength.amount * (count / 2f - charIndex),
        offsetYEm = -t * 0.025f * strength.amount,
        glowAlpha = (t * strength.blur).coerceIn(0f, 1f),
        glowRadiusEm = (strength.blur * 0.3f).coerceAtMost(0.3f),
        floatOffsetEm = -wave * EmpFloatEm * (if (isBackgroundLine) 2f else 1f),
    )
}

/** The float timeline AMLL gives each character: 400ms ahead of the glow, 1.4 times as long. */
internal fun lyricCharFloatStartMillis(charGlowStartMillis: Float): Float =
    charGlowStartMillis - EmpFloatDelayMillis

internal fun lyricCharFloatDurationMillis(strength: LyricEmphasizeStrength): Float =
    strength.durationMillis * EmpFloatDurationMultiplier

/**
 * Solves the cubic bezier AMLL's `bezier-easing` uses: Newton-Raphson on x to find the curve
 * parameter, then evaluates y. Eight passes is what the library itself runs before it gives up.
 */
private fun cubicBezierEase(x1: Float, y1: Float, x2: Float, y2: Float, x: Float): Float {
    if (x <= 0f) return 0f
    if (x >= 1f) return 1f
    val ax = 1f - 3f * x2 + 3f * x1
    val bx = 3f * x2 - 6f * x1
    val cx = 3f * x1
    val ay = 1f - 3f * y2 + 3f * y1
    val by = 3f * y2 - 6f * y1
    val cy = 3f * y1
    fun sampleX(t: Float) = ((ax * t + bx) * t + cx) * t
    fun sampleY(t: Float) = ((ay * t + by) * t + cy) * t
    fun slopeX(t: Float) = (3f * ax * t + 2f * bx) * t + cx

    var t = x
    repeat(8) {
        val slope = slopeX(t)
        if (slope == 0f) return@repeat
        t -= (sampleX(t) - x) / slope
    }
    return sampleY(t)
}
