package dev.naominet.lazer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val SelectionLightMillis = 260
private const val SelectionLightReleaseMillis = 150
private const val SelectionCascadeStepMillis = 16
private const val SelectionCascadeMaxMillis = 260
private const val SelectionUnselectedDim = 0.46f
private const val SelectionColorBlend = 0.5f

/**
 * Selection state for batch lyric copy. Lines are keyed rather than addressed by display index: an
 * interlude row enters and leaves the sheet while the song plays, and index-addressed selection
 * would slide onto the neighbouring lines underneath the user's finger.
 */
data class LyricSelectionState(
    val anchorKey: String? = null,
    val selectedKeys: Set<String> = emptySet(),
) {
    val isActive: Boolean get() = anchorKey != null
    val selectedCount: Int get() = selectedKeys.size

    fun clear(): LyricSelectionState = LyricSelectionState()
}

/** Identity of one selectable line, or null for the transient interlude row. */
fun lyricLineKey(line: TimedLyricLine): String? =
    line.text.trim().takeIf { it.isNotEmpty() }?.let { "${line.timeMs}|$it" }

fun lyricLineKeys(lines: List<TimedLyricLine>): List<String?> = lines.map { lyricLineKey(it) }

fun LyricSelectionState.isSelected(keys: List<String?>, index: Int): Boolean {
    val key = keys.getOrNull(index) ?: return false
    return key in selectedKeys
}

fun LyricSelectionState.begin(keys: List<String?>, index: Int): LyricSelectionState {
    val key = keys.getOrNull(index) ?: return this
    return copy(anchorKey = key, selectedKeys = setOf(key))
}

/**
 * Adds or drops one line. Dropping the anchor keeps the rest of the picks and re-anchors on the
 * closest surviving line, so a later drag still extends from where the selection actually is.
 */
fun LyricSelectionState.toggle(keys: List<String?>, index: Int): LyricSelectionState {
    val key = keys.getOrNull(index) ?: return this
    if (!isActive) return begin(keys, index)
    if (key !in selectedKeys) return copy(selectedKeys = selectedKeys + key)
    val remaining = selectedKeys - key
    if (remaining.isEmpty()) return clear()
    return copy(
        anchorKey = if (anchorKey == key) closestSurvivingKey(keys, index, remaining) else anchorKey,
        selectedKeys = remaining,
    )
}

/** Extends from the anchor through [index], which is how a held long-press drags across the sheet. */
fun LyricSelectionState.extendTo(keys: List<String?>, index: Int): LyricSelectionState {
    val anchor = anchorKey ?: return begin(keys, index)
    val key = keys.getOrNull(index) ?: return this
    val anchorIndex = keys.indexOf(anchor)
    if (anchorIndex < 0) return copy(anchorKey = key, selectedKeys = selectedKeys + key)
    val range = if (anchorIndex <= index) anchorIndex..index else index..anchorIndex
    return copy(selectedKeys = range.mapNotNull { keys[it] }.toSet())
}

fun LyricSelectionState.selectAll(keys: List<String?>): LyricSelectionState {
    val selectable = keys.mapNotNull { it }
    if (selectable.isEmpty()) return this
    return copy(anchorKey = anchorKey ?: selectable.first(), selectedKeys = selectable.toSet())
}

/** Formats the picked lines for the clipboard, keeping each translation under its own source line. */
fun buildLyricClipboardText(lines: List<TimedLyricLine>, selectedKeys: Set<String>): String =
    lines.mapNotNull { line ->
        val key = lyricLineKey(line) ?: return@mapNotNull null
        if (key !in selectedKeys) return@mapNotNull null
        buildString {
            append(line.text.trim())
            line.translation?.trim()?.takeIf(String::isNotBlank)?.let {
                append('\n')
                append(it)
            }
        }
    }.joinToString("\n")

private fun closestSurvivingKey(keys: List<String?>, fromIndex: Int, surviving: Set<String>): String =
    surviving.minByOrNull { abs(keys.indexOf(it) - fromIndex) } ?: surviving.first()

/**
 * How lit one row's selection highlight is, 0–1. The light arrives on the page's own easing curve,
 * so a dragged range lights up line by line under the finger instead of repainting the whole sheet.
 */
@Composable
fun animatedLyricSelectionPresence(
    selected: Boolean,
    speed: LyricAnimationSpeed,
    cascadeDelayMillis: Int = 0,
): Float {
    val presence by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = when {
            // A single line takes the same spring a lyric change takes, so marking the sheet and
            // following the song read as one mechanism rather than two motion systems.
            selected && cascadeDelayMillis == 0 -> spring(
                dampingRatio = LyricFocusDampingRatio,
                stiffness = lyricFocusStiffness(speed),
            )
            // A spring cannot wait its turn, so a bulk change rides staggered tweens instead.
            selected -> tween(
                durationMillis = SelectionLightMillis,
                delayMillis = cascadeDelayMillis.coerceIn(0, SelectionCascadeMaxMillis),
                easing = LazerTokens.Motion.pageEasing,
            )
            else -> tween(
                durationMillis = SelectionLightReleaseMillis,
                easing = LazerTokens.Motion.pageEasing,
            )
        },
        label = "lyric selection highlight",
    )
    return presence
}

/** Stagger for a bulk change such as select-all, measured from the row the anchor sits on. */
fun lyricSelectionCascadeDelay(index: Int, anchorIndex: Int): Int =
    min(SelectionCascadeMaxMillis, abs(index - anchorIndex) * SelectionCascadeStepMillis)

/**
 * How focused a row is: a marked line borrows the singing line's own focus value, so selection is
 * lit by the same mechanism the sheet already uses instead of a second visual language.
 */
fun lyricSelectionHighlight(focus: Float, presence: Float): Float =
    max(focus.coerceIn(0f, 1f), presence.coerceIn(0f, 1f))

/**
 * Steps the unmarked lines back while a passage is being picked. Marked lines need no lift here:
 * they already reach the singing line's own brightness through [lyricSelectionHighlight].
 */
fun lyricSelectionRowAlpha(baseAlpha: Float, modePresence: Float, rowPresence: Float): Float =
    (baseAlpha * (1f - SelectionUnselectedDim * modePresence * (1f - rowPresence))).coerceIn(0f, 1f)

/** Marked lines lean on the blue that stands for selection everywhere else in Lazer. */
fun lyricSelectionColor(base: Color, presence: Float, selection: Color): Color =
    lerp(base, selection, SelectionColorBlend * presence.coerceIn(0f, 1f))
