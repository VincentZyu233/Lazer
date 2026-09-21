package dev.naominet.lazer

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

private val MinimumLyricGlowOverflow = 36.dp

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
        // AMLL: mass 2, stiffness 100, damping 25. Compose fixes mass at one, so divide
        // stiffness/damping by mass and use the equivalent damping ratio.
        spring(
            dampingRatio = 0.884f,
            stiffness = (50.0 * speed.scrollMultiplier).toFloat(),
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
    var layoutResult by remember(text, style, textAlign, maxLines, overflow) {
        mutableStateOf<TextLayoutResult?>(null)
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
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "AMLL lyric effect",
    )
    val lineFocus by animateFloatAsState(
        targetValue = if (currentLine) 1f else 0f,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "AMLL lyric shadow",
    )
    val alignedStyle = style.merge(
        TextStyle(textAlign = textAlign ?: TextAlign.Unspecified),
    )
    val layoutStyle = alignedStyle.merge(TextStyle(color = Color.Transparent))
    val laidOutGlyphs = remember(layoutResult, glyphs) {
        layoutResult?.let { buildLaidOutGlyphs(it, glyphs) }.orEmpty()
    }
    val timedGlyphs = remember(laidOutGlyphs) {
        laidOutGlyphs.filter { it.timing.wordIndex >= 0 }
    }
    val untimedGlyphs = remember(laidOutGlyphs) {
        laidOutGlyphs.filter { it.timing.wordIndex < 0 }
    }
    val laidOutWords = remember(layoutResult, timedGlyphs, words) {
        layoutResult?.let { buildLaidOutWords(it, timedGlyphs, words) }.orEmpty()
    }
    // AMLL gives every timed word its own moving mask. The fade is half a word-height wide and
    // travels linearly from the word's start to end time, rather than clipping one global line.
    val maskWords = remember(laidOutWords) {
        var cachedPosition = Long.MIN_VALUE
        var cachedMasks = emptyList<LyricWordMask>()
        val compute: () -> List<LyricWordMask> = {
            val position = animatedPosition.value.roundToLong()
            if (position != cachedPosition) {
                cachedPosition = position
                cachedMasks = buildWordMasks(laidOutWords, position)
            }
            cachedMasks
        }
        compute
    }

    Box(modifier) {
        if (temporaryGlow || (glowEnabled && (currentLine || lineFocus > 0.001f))) {
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
                        val measured = layoutResult ?: return@drawBehind
                        drawRect(color = Color.Transparent, blendMode = BlendMode.Clear)
                        val inset = glowOverflowPadding.toPx()
                        translate(left = inset, top = inset) {
                            drawLyricShaderShadow(
                                layout = measured,
                                // Click feedback lights the whole line, including before seek completes.
                                hasTimedGlyphs = !temporaryGlow && timedGlyphs.isNotEmpty(),
                                wordMasks = if (temporaryGlow) emptyList() else maskWords(),
                                shadowColor = shadowColor,
                            )
                        }
                    }
                    .clearAndSetSemantics { },
            )
        }
        BasicText(
            text = text,
            modifier = Modifier.fillMaxWidth().drawWithContent {
                val measured = layoutResult
                if (measured == null) {
                    drawContent()
                } else if (words.isEmpty() || laidOutGlyphs.none { it.timing.wordIndex >= 0 }) {
                    drawText(measured, color = color)
                } else if (!active && effectStrength.value <= 0.001f) {
                    drawText(measured, color = color)
                } else {
                    drawAmllGlyphs(
                        layout = measured,
                        untimedGlyphs = untimedGlyphs,
                        laidOutWords = laidOutWords,
                        words = words,
                        wordMasks = maskWords(),
                        positionMillis = animatedPosition.value.roundToLong(),
                        color = color,
                        speed = speed,
                        effectStrength = effectStrength.value,
                    )
                }
            },
            style = layoutStyle,
            overflow = overflow,
            maxLines = maxLines,
            onTextLayout = { result ->
                if (layoutResult != result) layoutResult = result
            },
        )
    }
}

private data class LaidOutLyricGlyph(
    val timing: TimedLyricGlyph,
    val bounds: Rect,
    val path: Path,
)

private data class LaidOutLyricWord(
    val wordIndex: Int,
    val word: TimedLyricWord,
    val bounds: Rect,
    val glyphs: List<LaidOutLyricGlyph>,
    val path: Path,
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
        add(
            LaidOutLyricGlyph(
                timing = glyph,
                bounds = bounds,
                path = layout.getPathForRange(glyph.startOffset, glyph.endOffset),
            ),
        )
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
                glyphs = glyphs,
                path = Path().apply { glyphs.forEach { addPath(it.path) } },
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
    drawTextInWordMasks(layout, wordMasks, shadowColor)
}

private fun buildWordMasks(
    laidOutWords: List<LaidOutLyricWord>,
    positionMillis: Long,
): List<LyricWordMask> {
    if (laidOutWords.isEmpty()) return emptyList()
    val masks = ArrayList<LyricWordMask>(laidOutWords.size)
    for (laidOutWord in laidOutWords) {
        val bounds = laidOutWord.bounds
        val fadeWidth = bounds.height * 0.5f
        val progress = lyricWordMaskProgress(laidOutWord.word, positionMillis)
        val edgeX = bounds.left + lyricWordMaskEdge(progress, bounds.width, fadeWidth)
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
    untimedGlyphs: List<LaidOutLyricGlyph>,
    laidOutWords: List<LaidOutLyricWord>,
    words: List<TimedLyricWord>,
    wordMasks: List<LyricWordMask>,
    positionMillis: Long,
    color: Color,
    speed: LyricAnimationSpeed,
    effectStrength: Float,
) {
    val effect = effectStrength.coerceIn(0f, 1f)
    val masksByWord = wordMasks.associateBy(LyricWordMask::wordIndex)
    for (laidOutWord in laidOutWords) {
        val mask = masksByWord[laidOutWord.wordIndex]
        if (shouldEmphasizeLyricWord(laidOutWord.word)) {
            for (laidOutGlyph in laidOutWord.glyphs) {
                val glyph = laidOutGlyph.timing
                drawAmllFragment(
                    layout = layout,
                    path = laidOutGlyph.path,
                    bounds = laidOutGlyph.bounds,
                    motion = amllCharacterMotion(
                        word = laidOutWord.word,
                        positionMillis = positionMillis,
                        characterIndex = glyph.indexInWord,
                        characterCount = glyph.characterCount,
                        isLastWord = laidOutWord.wordIndex == words.lastIndex,
                        speed = speed,
                    ),
                    mask = mask,
                    color = color,
                    effect = effect,
                )
            }
        } else {
            drawAmllFragment(
                layout = layout,
                path = laidOutWord.path,
                bounds = laidOutWord.bounds,
                motion = AmllCharacterMotion(
                    scale = 1f,
                    offsetXEm = 0f,
                    offsetYEm = amllWordFloatOffsetEm(laidOutWord.word, positionMillis, speed),
                    glowAlpha = 0f,
                    glowRadiusEm = 0f,
                ),
                mask = mask,
                color = color,
                effect = effect,
            )
        }
    }
    for (glyph in untimedGlyphs) {
        drawAmllFragment(
            layout = layout,
            path = glyph.path,
            bounds = glyph.bounds,
            motion = AmllCharacterMotion(1f, 0f, 0f, 0f, 0f),
            mask = null,
            color = color,
            effect = effect,
        )
    }
}

private fun DrawScope.drawAmllFragment(
    layout: TextLayoutResult,
    path: Path,
    bounds: Rect,
    motion: AmllCharacterMotion,
    mask: LyricWordMask?,
    color: Color,
    effect: Float,
) {
    val emPixels = max(bounds.height, 1f)
    val scale = 1f + (motion.scale - 1f) * effect
    val offsetX = motion.offsetXEm * emPixels * effect
    val offsetY = motion.offsetYEm * emPixels * effect
    val pivot = bounds.center

    if (motion.glowAlpha * effect > 0.001f && motion.glowRadiusEm > 0f) {
        val blurPixels = motion.glowRadiusEm * emPixels
        withTransform({
            translate(offsetX, offsetY)
            scale(scale, scale, pivot)
        }) {
            clipRect(
                left = bounds.left - blurPixels * 3f,
                top = bounds.top - blurPixels * 3f,
                right = bounds.right + blurPixels * 3f,
                bottom = bounds.bottom + blurPixels * 3f,
            ) {
                drawText(
                    textLayoutResult = layout,
                    color = Color.Transparent,
                    shadow = Shadow(
                        color = color.copy(alpha = color.alpha * motion.glowAlpha * effect),
                        offset = Offset.Zero,
                        blurRadius = blurPixels,
                    ),
                )
            }
        }
    }

    val leftMask = mask?.foregroundAlphaAt(bounds.left) ?: 1f
    val rightMask = mask?.foregroundAlphaAt(bounds.right) ?: 1f
    val base = lyricBaseMaskAlpha()
    val leftAlpha = 1f - effect * (1f - (base + (1f - base) * leftMask))
    val rightAlpha = 1f - effect * (1f - (base + (1f - base) * rightMask))
    val foreground: Brush = if (abs(leftAlpha - rightAlpha) < 0.001f) {
        SolidColor(color.copy(alpha = color.alpha * leftAlpha))
    } else {
        Brush.horizontalGradient(
            colors = listOf(
                color.copy(alpha = color.alpha * leftAlpha),
                color.copy(alpha = color.alpha * rightAlpha),
            ),
            startX = bounds.left,
            endX = bounds.right,
        )
    }
    withTransform({
        translate(offsetX, offsetY)
        scale(scale, scale, pivot)
    }) {
        clipPath(path) {
            drawText(textLayoutResult = layout, brush = foreground)
        }
    }
}

private fun LyricWordMask.foregroundAlphaAt(x: Float): Float = when {
    fullyRevealed || x <= fadeStartX -> 1f
    x >= edgeX -> 0f
    else -> ((edgeX - x) / (edgeX - fadeStartX).coerceAtLeast(0.01f)).coerceIn(0f, 1f)
}

/** Paint AMLL's bright side over the dim base text, including the moving half-em fade band. */
private fun DrawScope.drawTextInWordMasks(
    layout: TextLayoutResult,
    wordMasks: List<LyricWordMask>,
    color: Color,
) {
    if (wordMasks.isEmpty()) return
    val solidMask = Path().apply {
        wordMasks.forEach { mask ->
            val solidRight = if (mask.fullyRevealed) {
                mask.bounds.right
            } else {
                mask.fadeStartX.coerceIn(mask.bounds.left, mask.bounds.right)
            }
            if (solidRight > mask.bounds.left) {
                addRect(Rect(mask.bounds.left, mask.bounds.top, solidRight, mask.bounds.bottom))
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
