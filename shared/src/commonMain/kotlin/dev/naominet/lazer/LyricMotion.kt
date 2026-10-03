package dev.naominet.lazer

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

// AMLL's scanner keeps the not-yet-sung part of the active line at 40% of its own colour. That has
// to stay above the brightest neighbour, which sits at 0.24 + 0.75 * 0.20 = 0.39 of the row alpha:
// at 0.40 the active line's 0.85 ceiling multiplied by the floor falls under the rows beside it, and
// the line being sung reads dimmer than the ones it should dominate.
private const val AMLL_BASE_MASK_ALPHA = 0.56f

/**
 * Stateless fallback retained for callers that only need a single eased value. Lyrics pages use
 * [LyricLineMotionField], which keeps velocity and gives each visible row its own delayed spring.
 */
fun nextLyricScrollPosition(
    position: Float,
    target: Float,
    seconds: Float,
    speed: LyricAnimationSpeed,
): Float {
    val distance = target - position
    if (abs(distance) <= 0.05f) return target
    val deltaSeconds = seconds.coerceIn(0f, 0.05f)
    val approach = (1.0 - exp(-lyricScrollApproachCoefficient(speed) * deltaSeconds)).toFloat()
    return position + distance * approach
}

internal data class LyricScrollSpringParameters(
    val mass: Float,
    val stiffness: Float,
    val damping: Float,
)

/** AMLL's vertical spring policy, including the interval-dependent stiffness curve. */
internal fun lyricScrollSpringParameters(
    intervalMillis: Long?,
    speed: LyricAnimationSpeed,
): LyricScrollSpringParameters {
    val baseStiffness = if (intervalMillis == null) {
        90f
    } else {
        val interval = intervalMillis.coerceIn(100L, 800L).toFloat()
        val ratio = (1f - (interval - 100f) / 700f).pow(0.2f)
        170f + ratio * 50f
    }
    val stiffness = baseStiffness * speed.scrollMultiplier.toFloat().pow(1.2f)
    return LyricScrollSpringParameters(
        mass = 0.9f,
        stiffness = stiffness,
        damping = sqrt(stiffness) * 2.2f,
    )
}

/**
 * One persistent spring per lyric row. A target change is released from top to bottom with AMLL's
 * roughly 50 ms stagger, so the lyrics no longer move as one rigid, linearly translated sheet.
 */
class LyricLineMotionField {
    private var positions = FloatArray(0)
    private var velocities = FloatArray(0)
    private var delays = FloatArray(0)
    private var targetPosition = 0f

    fun reset(lineCount: Int, position: Float) {
        positions = FloatArray(lineCount.coerceAtLeast(0)) { position }
        velocities = FloatArray(positions.size)
        delays = FloatArray(positions.size)
        targetPosition = position
    }

    fun snapTo(position: Float) {
        targetPosition = position
        positions.fill(position)
        velocities.fill(0f)
        delays.fill(0f)
    }

    fun positionFor(index: Int): Float = positions.getOrElse(index) { targetPosition }

    /** Preserve each existing row's spring when a transient interlude row enters or leaves. */
    fun remap(previousIndices: List<Int>, fallbackPosition: Float) {
        val oldPositions = positions
        val oldVelocities = velocities
        val oldDelays = delays
        positions = FloatArray(previousIndices.size) { oldPositions.getOrElse(previousIndices[it]) { fallbackPosition } }
        velocities = FloatArray(previousIndices.size) { oldVelocities.getOrElse(previousIndices[it]) { 0f } }
        delays = FloatArray(previousIndices.size) { oldDelays.getOrElse(previousIndices[it]) { 0f } }
    }

    fun advance(
        target: Float,
        activeIndex: Int,
        seconds: Float,
        intervalMillis: Long?,
        speed: LyricAnimationSpeed,
    ): Boolean {
        if (positions.isEmpty()) return false
        if (abs(target - targetPosition) > 0.01f) {
            targetPosition = target
            scheduleCascade(activeIndex, speed)
        }

        val params = lyricScrollSpringParameters(intervalMillis, speed)
        val frameSeconds = seconds.coerceIn(0f, 0.05f)
        var moving = false
        for (index in positions.indices) {
            var availableSeconds = frameSeconds
            if (delays[index] > 0f) {
                val consumed = min(delays[index], availableSeconds)
                delays[index] -= consumed
                availableSeconds -= consumed
                moving = true
            }
            if (availableSeconds > 0f) {
                integrate(index, availableSeconds, params)
            }
            val distance = targetPosition - positions[index]
            if (abs(distance) < 0.02f && abs(velocities[index]) < 0.02f && delays[index] <= 0f) {
                positions[index] = targetPosition
                velocities[index] = 0f
            } else {
                moving = true
            }
        }
        return moving
    }

    private fun scheduleCascade(activeIndex: Int, speed: LyricAnimationSpeed) {
        val firstVisibleApproximation = (activeIndex - 3).coerceAtLeast(0)
        for (index in delays.indices) {
            val step = (index - firstVisibleApproximation).coerceIn(0, 7)
            var delay = 0f
            repeat(step) { delayStep ->
                val decayStep = (delayStep - 3).coerceAtLeast(0)
                delay += 0.05f / 1.05f.pow(decayStep)
            }
            delays[index] = delay / speed.scrollMultiplier.toFloat()
        }
    }

    private fun integrate(
        index: Int,
        seconds: Float,
        params: LyricScrollSpringParameters,
    ) {
        val steps = ceil(seconds / (1f / 120f)).toInt().coerceAtLeast(1)
        val stepSeconds = seconds / steps
        repeat(steps) {
            val displacement = targetPosition - positions[index]
            val acceleration =
                (params.stiffness * displacement - params.damping * velocities[index]) / params.mass
            velocities[index] += acceleration * stepSeconds
            positions[index] += velocities[index] * stepSeconds
        }
    }
}

/** How far the whole-line scanner has travelled, 0–1, weighted equally per timed word. */
internal fun lyricLineScanFraction(
    words: List<TimedLyricWord>,
    positionMillis: Long,
    speed: LyricAnimationSpeed,
): Float {
    if (words.isEmpty()) return 0f
    val index = currentLyricWordIndex(words, positionMillis)
    if (index < 0) return 0f
    val progress = lyricWordVisualProgress(words[index], positionMillis, speed).coerceIn(0f, 1f)
    return ((index + progress) / words.size).coerceIn(0f, 1f)
}

/** Last word whose start is at or before the playhead, or -1 if none have started. */
internal fun currentLyricWordIndex(words: List<TimedLyricWord>, positionMillis: Long): Int {
    var result = -1
    for (index in words.indices) {
        if (words[index].startTimeMillis <= positionMillis) result = index else break
    }
    return result
}

/** Fractional progress keeps a long syllable moving throughout its source duration. */
fun lyricWordProgress(word: TimedLyricWord, positionMillis: Long): Float =
    ((positionMillis - word.startTimeMillis).toDouble() / word.durationMillis.coerceAtLeast(1L))
        .coerceIn(0.0, 1.0).toFloat()

/**
 * A forgiving visual clock around the source timestamp. Its overlap lets neighbouring syllables
 * share the moving fade edge without changing the lyric line selected by the playback clock.
 */
fun lyricWordVisualProgress(
    word: TimedLyricWord,
    positionMillis: Long,
    speed: LyricAnimationSpeed,
): Float {
    val leadMillis = 70.0 / speed.scrollMultiplier
    val tailMillis = 15.0 / speed.scrollMultiplier
    val visualStart = word.startTimeMillis.toDouble() - leadMillis
    val visualDuration = word.durationMillis.coerceAtLeast(1L) + leadMillis + tailMillis
    val rawProgress = ((positionMillis - visualStart) / visualDuration).coerceIn(0.0, 1.0)
    return rawProgress.pow(speed.highlightExponent).toFloat()
}

/**
 * AMLL's mask moves linearly through the word being sung: no easing, and the smoothing belongs to
 * the renderer's clock interpolation rather than to the mask itself. This is the fraction of one
 * word that has been sung, which is what drives the whole line's sweep.
 */
internal fun lyricWordMaskProgress(
    word: TimedLyricWord,
    positionMillis: Long,
): Float = lyricWordProgress(word, positionMillis)

/**
 * AMLL's mask accumulator: the width of every word sung so far. It grows while a word is being sung
 * and holds through the pauses between them, so the lit edge is one wave crossing the whole line
 * rather than a feather that restarts at each word and leaves a hard edge behind it.
 */
internal fun lyricLineSweptWidth(
    wordWidths: List<Float>,
    fractions: List<Float>,
): Float {
    var swept = 0f
    wordWidths.forEachIndexed { index, width ->
        swept += width.coerceAtLeast(0f) * fractions.getOrElse(index) { 0f }.coerceIn(0f, 1f)
    }
    return swept
}

/**
 * AMLL's extra runway at the two ends of a line, in feather widths: the first word sweeps one and a
 * half further and the last half further. The head start is what centres the feather on the sung
 * position for every word after the first, and the tail is what lands the lit edge exactly on the
 * end of the line instead of half a feather short of it.
 */
internal fun lyricLineSweepAllowance(firstWordFraction: Float, lastWordFraction: Float): Float =
    1.5f * firstWordFraction.coerceIn(0f, 1f) + 0.5f * lastWordFraction.coerceIn(0f, 1f)

/**
 * How far a word's lit edge has travelled from its own left edge, given how much of the line has
 * been swept. The feather rides centred on the sung position, so a word is half a feather lit when
 * its turn starts and finishes half a feather into the next one.
 *
 * [headStart] lifts a word's sweep by that much. Upstream's first word sits dark until the sweep has
 * crossed one feather of nothing, because upstream also pulls every line's start time forward by up
 * to 600ms to pay for it; this sheet takes the clock as it comes, so the line's first word starts at
 * its own left edge instead.
 */
internal fun lyricWordMaskTravel(
    sweptWidth: Float,
    widthBefore: Float,
    wordWidth: Float,
    fadeWidth: Float,
    headStart: Float = 0f,
): Float = (sweptWidth - widthBefore - fadeWidth + headStart.coerceAtLeast(0f))
    .coerceIn(0f, wordWidth.coerceAtLeast(0f) + fadeWidth.coerceAtLeast(0f))


internal fun lyricBaseMaskAlpha(): Float = AMLL_BASE_MASK_ALPHA

/** A disabled AMLL line pauses its mask clock instead of rewinding to an inactive placeholder. */
internal fun lyricMaskTargetPositionMillis(
    active: Boolean,
    reportedPositionMillis: Long,
    retainedActivePositionMillis: Long,
): Long = if (active) reportedPositionMillis else retainedActivePositionMillis

/** AMLL main-line scale spring target: inactive 97%, focused 100%. */
fun amllLyricLineScale(focus: Float): Float =
    0.97f + focus.coerceIn(0f, 1f) * 0.03f

/**
 * AMLL's distance-based inactive-line blur, reduced on viewports up to 1024 px and capped so a
 * far line never dissolves completely. [maxBlurDp] raises the ceiling where a larger surface can
 * afford more depth.
 */
fun amllLyricBlurRadiusDp(
    distance: Float,
    focus: Float,
    narrowViewport: Boolean,
    interactionSuspended: Boolean,
    maxBlurDp: Float = 5f,
): Float {
    if (interactionSuspended) return 0f
    val level = (1f + distance.coerceAtLeast(0f)) * if (narrowViewport) 0.8f else 1f
    return min(maxBlurDp, level * (1f - focus.coerceIn(0f, 1f)))
}

fun lyricWordSmoothingMillis(speed: LyricAnimationSpeed): Int =
    (96.0 / speed.scrollMultiplier).roundToInt().coerceIn(56, 180)

internal data class TimedLyricGlyph(
    val startOffset: Int,
    val endOffset: Int,
    val wordIndex: Int,
    val indexInWord: Int,
    val characterCount: Int,
    val isVisible: Boolean,
)

/** Maps stable text-layout offsets to timed words while preserving emoji and combining sequences. */
internal fun buildTimedLyricGlyphs(
    text: String,
    words: List<TimedLyricWord>,
): List<TimedLyricGlyph> {
    if (text.isEmpty()) return emptyList()
    val wordRanges = mutableListOf<IntRange>()
    var cursor = 0
    for (word in words) {
        val found = text.indexOf(word.text, startIndex = cursor).takeIf { it >= 0 } ?: cursor
        val endExclusive = (found + word.text.length).coerceAtMost(text.length)
        wordRanges += found until endExclusive
        cursor = endExclusive
    }

    data class RawGlyph(val start: Int, val end: Int, val wordIndex: Int, val visible: Boolean)
    val raw = splitGraphemes(text).map { range ->
        val wordIndex = wordRanges.indexOfFirst { range.first >= it.first && range.last <= it.last }
        RawGlyph(
            start = range.first,
            end = range.last + 1,
            wordIndex = wordIndex,
            visible = text.substring(range.first, range.last + 1).isNotBlank(),
        )
    }
    val counts = IntArray(words.size)
    raw.forEach { if (it.wordIndex >= 0 && it.visible) counts[it.wordIndex]++ }
    val indexes = IntArray(words.size)
    return raw.map { glyph ->
        val indexInWord = if (glyph.wordIndex >= 0 && glyph.visible) indexes[glyph.wordIndex]++ else 0
        TimedLyricGlyph(
            startOffset = glyph.start,
            endOffset = glyph.end,
            wordIndex = glyph.wordIndex,
            indexInWord = indexInWord,
            characterCount = counts.getOrElse(glyph.wordIndex) { 0 },
            isVisible = glyph.visible,
        )
    }
}

private fun splitGraphemes(text: String): List<IntRange> = buildList {
    var start = 0
    while (start < text.length) {
        var end = nextCodePointEnd(text, start)
        val firstCodePoint = codePointAt(text, start)
        if (firstCodePoint in 0x1F1E6..0x1F1FF && end < text.length &&
            codePointAt(text, end) in 0x1F1E6..0x1F1FF
        ) {
            end = nextCodePointEnd(text, end)
        }
        while (end < text.length) {
            val codePoint = codePointAt(text, end)
            when {
                isCombiningCodePoint(codePoint) -> end = nextCodePointEnd(text, end)
                codePoint == 0x200D -> {
                    end = nextCodePointEnd(text, end)
                    if (end < text.length) end = nextCodePointEnd(text, end)
                }
                else -> break
            }
        }
        add(start until end)
        start = end
    }
}

private fun nextCodePointEnd(text: String, offset: Int): Int =
    offset + if (text[offset].isHighSurrogate() && offset + 1 < text.length && text[offset + 1].isLowSurrogate()) 2 else 1

private fun codePointAt(text: String, offset: Int): Int {
    val first = text[offset]
    if (!first.isHighSurrogate() || offset + 1 >= text.length) return first.code
    val second = text[offset + 1]
    if (!second.isLowSurrogate()) return first.code
    return 0x10000 + ((first.code - 0xD800) shl 10) + (second.code - 0xDC00)
}

private fun isCombiningCodePoint(codePoint: Int): Boolean =
    codePoint in 0x0300..0x036F ||
        codePoint in 0x1AB0..0x1AFF ||
        codePoint in 0x1DC0..0x1DFF ||
        codePoint in 0x20D0..0x20FF ||
        codePoint in 0xFE00..0xFE0F ||
        codePoint in 0xFE20..0xFE2F ||
        codePoint in 0x1F3FB..0x1F3FF ||
        codePoint in 0xE0100..0xE01EF
