package dev.naominet.lazer

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import kotlin.math.roundToLong

private val MinimumLyricGlowOverflow = 36.dp

/** Brightness of the line being sung. Below opaque so glow and cover art still read through it. */
const val LyricActiveLineAlpha = 0.85f

/**
 * Brightness of every other line. AMLL's wrapper opacity is a flat 1 there and its two ink layers
 * hold 0.2 each, so all lines out of the singing window read at 0.4 of the ink - near or far, which
 * is why distance moves the blur and never the brightness.
 */
const val LyricInactiveLineAlpha = 0.4f

/**
 * AMLL's translation and romanisation sub-line: the same ink at 0.3, not a second colour. It rides
 * the row's own wrapper opacity, so a sub-line of the singing line is brighter than one elsewhere.
 */
const val LyricSubLineOpacity = 0.3f

/**
 * AMLL's line-focus spring: mass 2, stiffness 100, damping 25. Compose fixes mass at one, so
 * stiffness and damping are divided by mass. Selection ink uses the same pair, which is what makes
 * marking a line feel like the same mechanism as the sheet following the song.
 */
internal const val LyricFocusDampingRatio = 0.884f
internal fun lyricFocusStiffness(speed: LyricAnimationSpeed): Float =
    50f * speed.scrollMultiplier.toFloat()

/** Enlarges only the render layer; the lyric keeps its original measured row size. */
private fun Modifier.expandLayerForGlow(padding: Dp): Modifier = layout { measurable, constraints ->
    val paddingPx = padding.roundToPx()
    val originalWidth = constraints.maxWidth
    val originalHeight = constraints.maxHeight
    val placeable = measurable.measure(
        Constraints.fixed(
            width = originalWidth + paddingPx * 2,
            height = originalHeight + paddingPx * 2,
        ),
    )
    layout(originalWidth, originalHeight) {
        placeable.place(-paddingPx, -paddingPx)
    }
}

@Composable
fun animatedLyricFocus(active: Boolean, speed: LyricAnimationSpeed): Float {
    val focus by animateFloatAsState(
        if (active) 1f else 0f,
        spring(
            dampingRatio = LyricFocusDampingRatio,
            stiffness = lyricFocusStiffness(speed),
        ),
        label = "lyric focus",
    )
    return focus
}

/**
 * AMLL-style lyric renderer.
 *
 * BasicText performs shaping and line breaking once. The timed mask, character transforms, and
 * glow are then painted over that immutable layout, so animation can never change row height.
 */
@Composable
fun AmllLyricText(
    text: String,
    words: List<TimedLyricWord>,
    positionMillis: Long,
    active: Boolean,
    currentLine: Boolean = active,
    color: Color,
    shadowColor: Color = Color.Black,
    glowEnabled: Boolean = true,
    speed: LyricAnimationSpeed,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle.Default,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    temporaryGlow: Boolean = false,
    contentBlurRadiusPixels: Float = 0f,
) {
    val density = LocalDensity.current
    val lyricEm = if (style.fontSize.type == TextUnitType.Sp) {
        with(density) { style.fontSize.toDp() }
    } else {
        MinimumLyricGlowOverflow
    }
    // AMLL reserves one em around the main line/word/character. The blur also needs roughly
    // three radii of transparent pixels or Skia's offscreen layer cuts the Gaussian tail.
    val shaderShadowRadius = lyricEm.times(0.3f).coerceIn(6.dp, 18.dp)
    val glowOverflowPadding = maxOf(
        lyricEm,
        shaderShadowRadius.times(3f),
        MinimumLyricGlowOverflow,
    )
    // AMLL expresses the emphasis offsets in em, so the row's own em is what converts them.
    val emPx = with(density) { lyricEm.toPx() }
    // onTextLayout is called during measure, before the draw pass. Keep the result in a stable
    // holder so custom drawing sees the layout measured for this frame instead of the state value
    // from the previous frame.
    val layoutResult = remember(text, style, textAlign, maxLines, overflow) {
        LatestLyricLayout()
    }
    val glyphs = remember(text, words) { buildTimedLyricGlyphs(text, words) }
    // AMLL pauses a line's word/mask animation when that line is disabled. Keep the last active
    // clock here instead of accepting the placeholder timestamp supplied by inactive rows. Without
    // this retention the just-finished Android line rewound to its start while effectStrength was
    // still fading, producing one visibly dark/bright frame at every lyric change.
    var retainedActivePosition by remember(text, words) { mutableLongStateOf(positionMillis) }
    SideEffect {
        if (active) retainedActivePosition = positionMillis
    }
    val maskPositionMillis = lyricMaskTargetPositionMillis(
        active = active,
        reportedPositionMillis = positionMillis,
        retainedActivePositionMillis = retainedActivePosition,
    )
    // Keep high-frequency clock/effect reads in draw rather than composition. Playback updates
    // now invalidate only this lyric's display list, not its BasicText/layout subtree.
    val animatedPosition = animateFloatAsState(
        targetValue = maskPositionMillis.toFloat(),
        animationSpec = tween(
            durationMillis = lyricWordSmoothingMillis(speed),
            easing = LinearEasing,
        ),
        label = "AMLL lyric clock",
    )
    val effectStrength = animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        // AMLL cross-fades the dim and bright ink layers over 0.45s on CSS `ease-out`.
        animationSpec = tween(durationMillis = 450, easing = LinearOutSlowInEasing),
        label = "AMLL lyric effect",
    )
    val lineFocus by animateFloatAsState(
        targetValue = if (currentLine) 1f else 0f,
        // The wrapper's own opacity and filter transitions are 0.4s ease.
        animationSpec = tween(durationMillis = 400, easing = LinearOutSlowInEasing),
        label = "AMLL lyric shadow",
    )
    val alignedStyle = style.merge(
        TextStyle(textAlign = textAlign ?: TextAlign.Unspecified),
    )
    val layoutStyle = alignedStyle.merge(TextStyle(color = Color.Transparent))
    // onTextLayout fires during measure, after composition has already read layoutResult, so any
    // data derived from that state in composition lags the text on the canvas by a frame. Both
    // shortcuts were visible at a line change: drawing unmasked flashed the line bright, holding it
    // at its dim base flashed it grey. Resolving against the layout actually being drawn keeps the
    // mask and the glyphs in the same frame.
    val timedLayout = remember { TimedLyricLayout() }

    Box(modifier) {
        // AMLL's glow is a bloom: it is white and it adds light, which only reads on a dark ground.
        // Painting a blurred copy of the line in a dark ink is not a bloom but a drop shadow, and the
        // lyric would be rendered twice, so a light theme gets no underlay at all.
        val glowBrightens = shadowColor.luminance() > 0.5f
        if (glowBrightens && (temporaryGlow || (glowEnabled && (currentLine || lineFocus > 0.001f)))) {
            Box(
                Modifier
                    .matchParentSize()
                    .expandLayerForGlow(glowOverflowPadding)
                    .graphicsLayer {
                        val radius = shaderShadowRadius.toPx()
                        compositingStrategy = CompositingStrategy.Offscreen
                        clip = false
                        alpha = 0.5f * if (temporaryGlow) 1f else lineFocus
                        renderEffect = if (temporaryGlow || lineFocus > 0.001f) {
                            BlurEffect(radius, radius, TileMode.Decal)
                        } else {
                            null
                        }
                    }
                    .drawBehind {
                        if (!temporaryGlow && lineFocus <= 0.001f) return@drawBehind
                        val measured = layoutResult.value ?: return@drawBehind
                        drawRect(color = Color.Transparent, blendMode = BlendMode.Clear)
                        val timed = timedLayout.resolve(measured, glyphs, words)
                        val clock = animatedPosition.value.roundToLong()
                        val inset = glowOverflowPadding.toPx()
                        translate(left = inset, top = inset) {
                            val masks = if (temporaryGlow) {
                                emptyList()
                            } else {
                                timed.masksAt(clock)
                            }
                            drawLyricShaderShadow(
                                layout = measured,
                                // Click feedback lights the whole line, including before seek completes.
                                hasTimedGlyphs = !temporaryGlow && timed.hasTimedGlyphs,
                                wordMasks = masks,
                                shadowColor = shadowColor,
                            )
                            // AMLL gives each character mid-emphasis its own blurred duplicate; the
                            // layer's Gaussian makes that a halo, so the only extra work here is the
                            // per-character opacity AMLL's easing curve produces. Only the line being
                            // sung animates, so a word-less row must not sample a frozen clock.
                            if (!temporaryGlow && active) {
                                timed.emphasisAt(clock, speed).forEach { state ->
                                    val alpha = state.emphasis.glowAlpha
                                    if (alpha <= 0.001f) return@forEach
                                    drawEmphasizedCharacter(
                                        layout = measured,
                                        state = state,
                                        wordMasks = masks,
                                        color = shadowColor.copy(alpha = shadowColor.alpha * alpha),
                                        emPx = emPx,
                                    )
                                }
                            }
                        }
                    }
                    .clearAndSetSemantics { },
            )
        }
        BasicText(
            text = text,
            modifier = Modifier
                .fillMaxWidth()
                // Blur only the foreground text. The glow is a sibling layer with its own
                // overflow gutter, so focus blur can animate continuously without clipping it.
                .graphicsLayer {
                    val radius = contentBlurRadiusPixels.coerceAtLeast(0f)
                    renderEffect = if (radius > 0.01f) {
                        BlurEffect(radius, radius, TileMode.Decal)
                    } else {
                        null
                    }
                    clip = false
                }
                .drawWithContent {
                val measured = layoutResult.value
                if (measured == null) {
                    drawContent()
                } else {
                    val timed = timedLayout.resolve(measured, glyphs, words)
                    if (words.isEmpty() || !timed.hasTimedGlyphs) {
                        drawText(measured, color = color)
                    } else if (!active && effectStrength.value <= 0.001f) {
                        drawText(measured, color = color)
                    } else {
                        val clock = animatedPosition.value.roundToLong()
                        drawAmllGlyphs(
                            layout = measured,
                            hasTimedGlyphs = true,
                            wordMasks = timed.masksAt(clock),
                            emphasized = timed.emphasisAt(clock, speed),
                            color = color,
                            effectStrength = effectStrength.value,
                            emPx = emPx,
                        )
                    }
                }
            },
            style = layoutStyle,
            overflow = overflow,
            maxLines = maxLines,
            onTextLayout = { result ->
                layoutResult.value = result
            },
        )
    }
}

private class LatestLyricLayout {
    var value: TextLayoutResult? = null
}

/**
 * Lazily resolves the timed glyph geometry against the layout that is actually being drawn.
 * Keeping this out of composition prevents a layout callback from leaving the mask one frame
 * behind the BasicText content.
 */
private class TimedLyricLayout {
    private var sourceLayout: TextLayoutResult? = null
    private var sourceGlyphs: List<TimedLyricGlyph> = emptyList()
    private var sourceWords: List<TimedLyricWord> = emptyList()
    private var timedGlyphs: List<LaidOutLyricGlyph> = emptyList()
    private var laidOutWords: List<LaidOutLyricWord> = emptyList()
    private var wordStrengths: List<LyricEmphasizeStrength> = emptyList()
    private var cachedPosition = Long.MIN_VALUE
    private var cachedSpeed: LyricAnimationSpeed? = null
    private var cachedMasks = emptyList<LyricWordMask>()
    private var cachedEmphasisPosition = Long.MIN_VALUE
    private var cachedEmphasisSpeed: LyricAnimationSpeed? = null
    private var cachedEmphasis = emptyList<LyricCharEmphasisState>()

    var hasTimedGlyphs: Boolean = false
        private set

    fun resolve(
        layout: TextLayoutResult,
        glyphs: List<TimedLyricGlyph>,
        words: List<TimedLyricWord>,
    ): TimedLyricLayout {
        if (sourceLayout !== layout || sourceGlyphs !== glyphs || sourceWords !== words) {
            sourceLayout = layout
            sourceGlyphs = glyphs
            sourceWords = words
            val laidOutGlyphs = buildLaidOutGlyphs(layout, glyphs)
            timedGlyphs = laidOutGlyphs.filter { it.timing.wordIndex >= 0 }
            laidOutWords = buildLaidOutWords(layout, timedGlyphs, words)
            wordStrengths = words.mapIndexed { index, word ->
                lyricEmphasizeStrength(
                    wordDurationMillis = word.durationMillis,
                    isLastWord = index == words.lastIndex,
                )
            }
            hasTimedGlyphs = timedGlyphs.isNotEmpty()
            cachedPosition = Long.MIN_VALUE
            cachedSpeed = null
            cachedMasks = emptyList()
            cachedEmphasisPosition = Long.MIN_VALUE
            cachedEmphasisSpeed = null
            cachedEmphasis = emptyList()
        }
        return this
    }

    fun masksAt(positionMillis: Long): List<LyricWordMask> {
        if (cachedPosition != positionMillis) {
            cachedPosition = positionMillis
            cachedMasks = buildWordMasks(laidOutWords, positionMillis)
        }
        return cachedMasks
    }

    /**
     * Where every timed character of the line stands this frame, with AMLL's emphasis transform
     * already resolved. Characters outside their glow window are left out entirely: AMLL's
     * `fill: "both"` parks them at the identity transform there, so the ordinary masked pass already
     * paints them correctly.
     */
    fun emphasisAt(positionMillis: Long, speed: LyricAnimationSpeed): List<LyricCharEmphasisState> {
        if (cachedEmphasisPosition == positionMillis && cachedEmphasisSpeed == speed) {
            return cachedEmphasis
        }
        cachedEmphasisPosition = positionMillis
        cachedEmphasisSpeed = speed
        val layout = sourceLayout
        if (layout == null || timedGlyphs.isEmpty()) {
            cachedEmphasis = emptyList()
            return cachedEmphasis
        }
        cachedEmphasis = buildList(timedGlyphs.size) {
            for (glyph in timedGlyphs) {
                val wordIndex = glyph.timing.wordIndex
                if (wordIndex !in sourceWords.indices) continue
                val word = sourceWords[wordIndex]
                if (!lyricWordIsEmphasizable(word)) continue
                val strength = wordStrengths[wordIndex]
                val glowProgress = lyricEmphasizeElapsed(
                    positionMillis = positionMillis,
                    startMillis = lyricEmphasizeCharStartMillis(
                        wordStartMillis = word.startTimeMillis,
                        strength = strength,
                        charIndex = glyph.timing.indexInWord,
                        characterCount = glyph.timing.characterCount,
                    ),
                    durationMillis = strength.durationMillis,
                )
                if (glowProgress <= 0f || glowProgress >= 1f) continue
                add(
                    LyricCharEmphasisState(
                        glyph = glyph,
                        emphasis = lyricCharEmphasis(
                            strength = strength,
                            glowProgress = glowProgress,
                            charIndex = glyph.timing.indexInWord,
                            characterCount = glyph.timing.characterCount,
                        ),
                        lineClip = glyphLineClip(layout, glyph),
                    ),
                )
            }
        }
        return cachedEmphasis
    }
}

/** A character that is mid-emphasis this frame, and how far along it is. */
internal class LyricCharEmphasisState(
    val glyph: LaidOutLyricGlyph,
    val emphasis: LyricCharEmphasis,
    /** The line box the character belongs to, so a transform never bleeds into the next row. */
    val lineClip: Rect,
)

internal data class LaidOutLyricGlyph(
    val timing: TimedLyricGlyph,
    val bounds: Rect,
)

private data class LaidOutLyricWord(
    val wordIndex: Int,
    val word: TimedLyricWord,
    val bounds: Rect,
)

private data class LyricWordMask(
    val wordIndex: Int,
    val bounds: Rect,
    val edgeX: Float,
    val fadeStartX: Float,
    val fullyRevealed: Boolean,
)

private fun buildLaidOutGlyphs(
    layout: TextLayoutResult,
    glyphs: List<TimedLyricGlyph>,
): List<LaidOutLyricGlyph> = buildList {
    for (glyph in glyphs) {
        if (!glyph.isVisible || glyph.startOffset >= layout.layoutInput.text.length) continue
        val bounds = layout.getBoundingBox(glyph.startOffset)
        if (bounds.width <= 0.01f || bounds.height <= 0.01f) continue
        add(LaidOutLyricGlyph(timing = glyph, bounds = bounds))
    }
}

private fun buildLaidOutWords(
    layout: TextLayoutResult,
    timedGlyphs: List<LaidOutLyricGlyph>,
    words: List<TimedLyricWord>,
): List<LaidOutLyricWord> = buildList {
    val groupedGlyphs = timedGlyphs.groupBy { it.timing.wordIndex }
    for (wordIndex in words.indices) {
        val glyphs = groupedGlyphs[wordIndex].orEmpty()
        if (glyphs.isEmpty()) continue
        add(
            LaidOutLyricWord(
                wordIndex = wordIndex,
                word = words[wordIndex],
                bounds = Rect(
                    left = glyphs.minOf { it.bounds.left },
                    top = glyphs.minOf { glyphLineClip(layout, it).top },
                    right = glyphs.maxOf { it.bounds.right },
                    bottom = glyphs.maxOf { glyphLineClip(layout, it).bottom },
                ),
            ),
        )
    }
}

private fun DrawScope.drawLyricShaderShadow(
    layout: TextLayoutResult,
    hasTimedGlyphs: Boolean,
    wordMasks: List<LyricWordMask>,
    shadowColor: Color,
) {
    if (!hasTimedGlyphs) {
        drawText(textLayoutResult = layout, color = shadowColor)
        return
    }
    drawRevealedWords(layout, wordMasks, emptyList(), shadowColor)
}

private fun buildWordMasks(
    laidOutWords: List<LaidOutLyricWord>,
    positionMillis: Long,
): List<LyricWordMask> {
    if (laidOutWords.isEmpty()) return emptyList()
    val masks = ArrayList<LyricWordMask>(laidOutWords.size)
    laidOutWords.forEachIndexed { index, laidOutWord ->
        val bounds = laidOutWord.bounds
        val fadeWidth = bounds.height * 0.5f
        // AMLL gives the line extra runway at its two ends, inside the same word duration: the first
        // word's feather starts a whole feather further back and the last travels half a feather on,
        // so the line opens and closes instead of popping. A one-word line gets both.
        val extraTravel = fadeWidth * (if (index == 0) 1.5f else 0f) +
            fadeWidth * (if (index == laidOutWords.lastIndex) 0.5f else 0f)
        // Upstream eases nothing: each word's reveal is linear across exactly its own span, and the
        // feather leaving the edge of the previous word is what holds between words.
        val progress = lyricWordMaskProgress(laidOutWord.word, positionMillis)
        val edgeX = bounds.left +
            lyricWordMaskEdge(progress, bounds.width, fadeWidth, extraTravel)
        val fadeStartX = edgeX - fadeWidth
        masks += LyricWordMask(
            wordIndex = laidOutWord.wordIndex,
            bounds = bounds,
            edgeX = edgeX,
            fadeStartX = fadeStartX,
            fullyRevealed = progress >= 1f,
        )
    }
    return masks
}

private fun glyphLineClip(layout: TextLayoutResult, glyph: LaidOutLyricGlyph): Rect {
    val line = layout.getLineForOffset(glyph.timing.startOffset)
    return Rect(
        left = glyph.bounds.left,
        top = layout.getLineTop(line),
        right = glyph.bounds.right,
        bottom = layout.getLineBottom(line),
    )
}

private fun DrawScope.drawAmllGlyphs(
    layout: TextLayoutResult,
    hasTimedGlyphs: Boolean,
    wordMasks: List<LyricWordMask>,
    emphasized: List<LyricCharEmphasisState>,
    color: Color,
    effectStrength: Float,
    emPx: Float,
) {
    val effect = effectStrength.coerceIn(0f, 1f)
    val dimColor = color.copy(alpha = color.alpha * (1f - effect * (1f - lyricBaseMaskAlpha())))
    // Whatever is being drawn on its own must not leave its original ink standing where it was.
    // AMLL can carry a static copy under the live one because that copy is only 0.2 strong; this
    // underlay is much nearer the real line, so an unmoved copy under a scaled glyph is a shadow.
    val vacated = vacatedInkBounds(wordMasks, emphasized)
    if (vacated.isEmpty()) {
        drawText(textLayoutResult = layout, color = dimColor)
    } else {
        clipPath(inkFreeArea(vacated)) {
            drawText(textLayoutResult = layout, color = dimColor)
        }
    }
    if (!hasTimedGlyphs) return
    drawRevealedWords(layout, wordMasks, emphasized, color)
    emphasized.forEach { state ->
        drawEmphasizedCharacter(layout, state, wordMasks, color, emPx)
    }
}

/**
 * Paints one mid-emphasis character as AMLL would have it: scaled about its own centre, leaned along
 * the line, and revealed only as far as its word's mask has got by now.
 *
 * The character is isolated by clipping rather than by drawing a text range, which is how the rest
 * of the line is masked too: the layout stays the single source of shaping and baseline position.
 */
private fun DrawScope.drawEmphasizedCharacter(
    layout: TextLayoutResult,
    state: LyricCharEmphasisState,
    wordMasks: List<LyricWordMask>,
    color: Color,
    emPx: Float,
) {
    val mask = wordMasks.firstOrNull { it.wordIndex == state.glyph.timing.wordIndex } ?: return
    val box = state.glyph.bounds
    val emphasis = state.emphasis
    val top = state.lineClip.top
    val bottom = state.lineClip.bottom

    translate(left = emphasis.offsetXEm * emPx) {
        scale(
            pivot = Offset((box.left + box.right) / 2f, (box.top + box.bottom) / 2f),
            scaleX = emphasis.scale,
            scaleY = emphasis.scale,
        ) {
            val revealedUpTo = if (mask.fullyRevealed) {
                mask.bounds.right
            } else {
                mask.fadeStartX.coerceIn(mask.bounds.left, mask.bounds.right)
            }
            val solidRight = box.right.coerceAtMost(revealedUpTo)
            if (solidRight > box.left) {
                clipRect(box.left, top, solidRight, bottom) {
                    drawText(textLayoutResult = layout, color = color)
                }
            }
            if (mask.fullyRevealed) return@scale
            val fadeLeft = mask.fadeStartX.coerceAtLeast(box.left)
            val fadeRight = mask.edgeX.coerceAtMost(box.right)
            if (fadeRight <= fadeLeft) return@scale
            clipRect(fadeLeft, top, fadeRight, bottom) {
                drawText(
                    textLayoutResult = layout,
                    brush = Brush.horizontalGradient(
                        colors = listOf(color, color.copy(alpha = 0f)),
                        startX = mask.fadeStartX,
                        endX = mask.edgeX,
                    ),
                )
            }
        }
    }
}

private fun LyricWordMask.solidRight(): Float = if (fullyRevealed) {
    bounds.right
} else {
    fadeStartX.coerceIn(bounds.left, bounds.right)
}

/**
 * Where the underlay must not paint, because a character is being drawn on its own somewhere else
 * this frame. Each hole is clipped to what its word's mask has already revealed, so a hole can never
 * brighten an un-sung region, and one per character keeps them disjoint for even-odd subtraction.
 */
private fun vacatedInkBounds(
    wordMasks: List<LyricWordMask>,
    emphasized: List<LyricCharEmphasisState>,
): List<Rect> {
    if (emphasized.isEmpty()) return emptyList()
    val bounds = ArrayList<Rect>(emphasized.size)
    emphasized.forEach { state ->
        val wordIndex = state.glyph.timing.wordIndex
        val hole = state.glyph.bounds
        wordMasks.forEach { mask ->
            if (mask.wordIndex != wordIndex) return@forEach
            val solid = Rect(mask.bounds.left, mask.bounds.top, mask.solidRight(), mask.bounds.bottom)
            if (hole.overlaps(solid)) bounds += hole.intersect(solid)
        }
    }
    return bounds
}

/** The draw area minus [holes]. Even-odd, so the holes have to stay disjoint. */
private fun DrawScope.inkFreeArea(holes: List<Rect>): Path = Path().apply {
    addRect(Rect(Offset.Zero, size))
    fillType = PathFillType.EvenOdd
    holes.forEach { addRect(it) }
}

private fun DrawScope.drawRevealedWords(
    layout: TextLayoutResult,
    wordMasks: List<LyricWordMask>,
    punched: List<LyricCharEmphasisState>,
    color: Color,
) {
    val solidMask = Path().apply {
        fillType = PathFillType.EvenOdd
        wordMasks.forEach { mask ->
            val right = mask.solidRight()
            if (right > mask.bounds.left) {
                addRect(Rect(mask.bounds.left, mask.bounds.top, right, mask.bounds.bottom))
            }
        }
        // Even-odd turns a character that is drawn on its own into a hole in this pass, but only
        // where this pass would have painted it, so a hole can never brighten an un-sung region.
        punched.forEach { state ->
            val hole = state.glyph.bounds
            wordMasks.forEach { mask ->
                if (mask.wordIndex != state.glyph.timing.wordIndex) return@forEach
                val solid = Rect(
                    mask.bounds.left,
                    mask.bounds.top,
                    mask.solidRight(),
                    mask.bounds.bottom,
                )
                if (hole.overlaps(solid)) addRect(hole.intersect(solid))
            }
        }
    }
    if (!solidMask.isEmpty) {
        clipPath(solidMask) {
            drawText(textLayoutResult = layout, color = color)
        }
    }
    wordMasks.forEach { mask ->
        if (mask.fullyRevealed) return@forEach
        val fadeLeft = mask.fadeStartX.coerceAtLeast(mask.bounds.left)
        val fadeRight = mask.edgeX.coerceAtMost(mask.bounds.right)
        if (fadeRight <= fadeLeft) return@forEach
        clipRect(fadeLeft, mask.bounds.top, fadeRight, mask.bounds.bottom) {
            drawText(
                textLayoutResult = layout,
                brush = Brush.horizontalGradient(
                    colors = listOf(color, color.copy(alpha = 0f)),
                    startX = mask.fadeStartX,
                    endX = mask.edgeX,
                ),
            )
        }
    }
}
