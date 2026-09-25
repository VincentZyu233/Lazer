package dev.naominet.lazer

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Shapes
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Slider as Material3Slider
import androidx.compose.material3.Switch as Material3Switch
import top.yukonga.miuix.kmp.basic.Slider as MiuixSlider
import top.yukonga.miuix.kmp.basic.Switch as MiuixSwitch
import top.yukonga.miuix.kmp.theme.Colors as MiuixColors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.TextStyles as MiuixTextStyles
import top.yukonga.miuix.kmp.theme.darkColorScheme as miuixDarkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme as miuixLightColorScheme

enum class LazerThemeEngine(private val labelKey: String) {
    MATERIAL3("theme.engine.material"),
    MIUIX("theme.engine.miuix");

    val label: String get() = tr(labelKey)
}

fun parseLazerThemeEngine(value: String?): LazerThemeEngine =
    LazerThemeEngine.entries.firstOrNull { it.name == value } ?: LazerThemeEngine.MATERIAL3

/**
 * Single user-facing appearance choice. Material, Miuix and Liquid Glass used to be separate
 * toggles; they are now one mutually exclusive style. Liquid Glass uses the Material component
 * engine underneath plus the glass effect layer.
 */
enum class LazerStyle(private val labelKey: String) {
    MATERIAL("style.material"),
    MIUIX("style.miuix"),
    LIQUID_GLASS("style.liquid_glass");

    val label: String get() = tr(labelKey)

    val themeEngine: LazerThemeEngine
        get() = when (this) {
            MATERIAL, LIQUID_GLASS -> LazerThemeEngine.MATERIAL3
            MIUIX -> LazerThemeEngine.MIUIX
        }

    val usesLiquidGlass: Boolean get() = this == LIQUID_GLASS
}

fun parseLazerStyle(value: String?): LazerStyle =
    LazerStyle.entries.firstOrNull { it.name == value } ?: LazerStyle.MATERIAL

val LocalLazerThemeEngine = staticCompositionLocalOf { LazerThemeEngine.MATERIAL3 }

@Composable
fun LazerThemeEngineSwitch(
    engine: LazerThemeEngine,
    onEngineChange: (LazerThemeEngine) -> Unit,
    modifier: Modifier = Modifier,
) {
    val useMiuix = engine == LazerThemeEngine.MIUIX
    LazerSwitch(
        engine = engine,
        checked = useMiuix,
        onCheckedChange = { enabled ->
            onEngineChange(if (enabled) LazerThemeEngine.MIUIX else LazerThemeEngine.MATERIAL3)
        },
        modifier = modifier,
    )
}

@Composable
fun LazerSwitch(
    engine: LazerThemeEngine,
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val useMiuix = engine == LazerThemeEngine.MIUIX
    if (useMiuix) {
        MiuixSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            enabled = enabled,
        )
    } else {
        Material3Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            enabled = enabled,
        )
    }
}

@Composable
fun LazerSlider(
    engine: LazerThemeEngine,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    if (engine == LazerThemeEngine.MIUIX) {
        // Miuix springs its own value and grows the knob while it is held, so it takes the value
        // as it comes. The Material slider does neither, and is animated here instead.
        MiuixSlider(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            enabled = enabled,
            valueRange = valueRange,
            steps = steps,
            onValueChangeFinished = onValueChangeFinished,
            showKeyPoints = steps > 0,
        )
    } else {
        MaterialLazerSlider(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            enabled = enabled,
            valueRange = valueRange,
            steps = steps,
            onValueChangeFinished = onValueChangeFinished,
        )
    }
}

/**
 * Value springs. A drag has to stay under the finger, so it uses a stiff one that only takes the
 * edge off the raw pointer, while a value arriving from anywhere else - a tap on the track, a
 * keyboard step, a setting restored with the rest of the screen - settles through a soft one that
 * makes the move visible. Both are bouncy-free, matching the springs the rest of the app moves on.
 */
private val SliderDragValueSpring = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 1755f)
private val SliderSettleValueSpring = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 360f)

/** Press halo: peak opacity, and how long it takes to bloom and to fade back out. */
private const val SliderHaloAlpha = 0.30f
private const val SliderHaloInMillis = 140
private const val SliderHaloOutMillis = 260

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MaterialLazerSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChangeFinished: (() -> Unit)?,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val colors = SliderDefaults.colors()
    val state = remember(steps, valueRange) {
        SliderState(steps = steps, valueRange = valueRange).apply {
            // Ticks still decide where a gesture lands; letting them snap the rendered value too
            // would quantize the glide back into the jumps it exists to hide.
            shouldAutoSnap = false
        }
    }
    state.onValueChange = onValueChange
    state.onValueChangeFinished = onValueChangeFinished

    val animatedValue by animateFloatAsState(
        targetValue = value.coerceIn(valueRange.start, valueRange.endInclusive),
        animationSpec = if (state.isDragging) SliderDragValueSpring else SliderSettleValueSpring,
        label = "lazer-slider-value",
    )
    state.value = animatedValue

    Material3Slider(
        state = state,
        modifier = modifier,
        enabled = enabled,
        colors = colors,
        interactionSource = interactionSource,
        thumb = { thumbState ->
            LazerSliderThumb(
                state = thumbState,
                interactionSource = interactionSource,
                colors = colors,
                enabled = enabled,
            )
        },
    )
}

/**
 * The stock handle with a halo that blooms once the thumb is held and fades when it is let go. The
 * halo is drawn behind the handle and outside its layout bounds, so the slider keeps measuring the
 * handle alone and none of its position mapping moves.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LazerSliderThumb(
    state: SliderState,
    interactionSource: MutableInteractionSource,
    colors: SliderColors,
    enabled: Boolean,
) {
    val pressed by interactionSource.collectIsPressedAsState()
    // A press turns into a drag once the finger travels, so the halo follows both.
    val held = enabled && (pressed || state.isDragging)
    val halo by animateFloatAsState(
        targetValue = if (held) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (held) SliderHaloInMillis else SliderHaloOutMillis,
            easing = FastOutSlowInEasing,
        ),
        label = "lazer-slider-halo",
    )
    val haloColor = MaterialTheme.colorScheme.primary
    Box(
        Modifier.drawBehind {
            val radius = size.height * 0.5f * (0.82f + 0.2f * halo)
            if (halo <= 0.01f || radius <= 0f) return@drawBehind
            drawCircle(
                brush = Brush.radialGradient(
                    // Fading to the same hue instead of transparent black keeps the glow from
                    // picking up a grey edge on the way out.
                    colors = listOf(
                        haloColor.copy(alpha = SliderHaloAlpha * halo),
                        haloColor.copy(alpha = 0f),
                    ),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
        },
    ) {
        SliderDefaults.Thumb(
            interactionSource = interactionSource,
            colors = colors,
            enabled = enabled,
        )
    }
}

/** Shared paper-and-blue tokens. All platform UIs inherit this theme. */
object LazerTokens {
    /** One navigation tempo keeps every page change feeling like part of the same product. */
    object Motion {
        const val pageMillis = 280

        /** The curve every surface uses when it answers a direct action. */
        val pageEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    }
    val Paper = Color(0xFFF7F5EF)
    val PaperRaised = Color(0xFFFCFAF5)
    val MistBlue = Color(0xFFA9C8D8)
    val LakeBlue = Color(0xFF5F91AC)
    val DeepBlue = Color(0xFF365F73)
    val Ink = Color(0xFF26363D)
    val QuietInk = Color(0xFF627279)
    val WarmClay = Color(0xFFB88769)
    val NightPaper = Color(0xFF1D282D)
    val NightRaised = Color(0xFF253238)
    val NightInk = Color(0xFFE7ECEB)
    val NightQuietInk = Color(0xFFAAB8BC)
    val NightBlue = Color(0xFF91BED3)
}

private val LightColors = lightColorScheme(
    primary = LazerTokens.LakeBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDECF3),
    onPrimaryContainer = Color(0xFF244B5E),
    secondary = Color(0xFF6E858F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE5ECEE),
    onSecondaryContainer = LazerTokens.Ink,
    tertiary = LazerTokens.WarmClay,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF1E3D8),
    onTertiaryContainer = Color(0xFF60442F),
    background = LazerTokens.Paper,
    onBackground = LazerTokens.Ink,
    surface = LazerTokens.PaperRaised,
    onSurface = LazerTokens.Ink,
    surfaceVariant = Color(0xFFE9EEED),
    onSurfaceVariant = LazerTokens.QuietInk,
    outline = Color(0xFFA8B5B8),
    outlineVariant = Color(0xFFD7DEDD),
    error = Color(0xFFA94C4C),
)

private val DarkColors = darkColorScheme(
    primary = LazerTokens.NightBlue,
    onPrimary = Color(0xFF153844),
    primaryContainer = Color(0xFF294B5A),
    onPrimaryContainer = Color(0xFFDCEFF7),
    secondary = Color(0xFFB2C6CE),
    onSecondary = Color(0xFF20343C),
    secondaryContainer = Color(0xFF304047),
    onSecondaryContainer = LazerTokens.NightInk,
    tertiary = Color(0xFFD2A388),
    onTertiary = Color(0xFF482A1B),
    tertiaryContainer = Color(0xFF563C2F),
    onTertiaryContainer = Color(0xFFF5DED0),
    background = LazerTokens.NightPaper,
    onBackground = LazerTokens.NightInk,
    surface = LazerTokens.NightRaised,
    onSurface = LazerTokens.NightInk,
    surfaceVariant = Color(0xFF314047),
    onSurfaceVariant = LazerTokens.NightQuietInk,
    outline = Color(0xFF687A81),
    outlineVariant = Color(0xFF3B4B51),
    error = Color(0xFFFFB4AB),
)

private val LazerTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp,
        lineHeight = 42.sp,
        letterSpacing = (-0.6).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 25.sp,
        lineHeight = 33.sp,
        letterSpacing = (-0.25).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 29.sp,
    ),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 25.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 16.sp),
)

private val MiuixMaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private const val ThemeTransitionMillis = 320

@Composable
private fun animatedThemeColor(target: Color, label: String): Color {
    val color by animateColorAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = ThemeTransitionMillis, easing = FastOutSlowInEasing),
        label = label,
    )
    return color
}

/** Interpolates every Material semantic color so a theme change does not flash between frames. */
@Composable
private fun rememberAnimatedColorScheme(target: ColorScheme): ColorScheme = target.copy(
    primary = animatedThemeColor(target.primary, "theme-primary"),
    onPrimary = animatedThemeColor(target.onPrimary, "theme-on-primary"),
    primaryContainer = animatedThemeColor(target.primaryContainer, "theme-primary-container"),
    onPrimaryContainer = animatedThemeColor(target.onPrimaryContainer, "theme-on-primary-container"),
    inversePrimary = animatedThemeColor(target.inversePrimary, "theme-inverse-primary"),
    secondary = animatedThemeColor(target.secondary, "theme-secondary"),
    onSecondary = animatedThemeColor(target.onSecondary, "theme-on-secondary"),
    secondaryContainer = animatedThemeColor(target.secondaryContainer, "theme-secondary-container"),
    onSecondaryContainer = animatedThemeColor(target.onSecondaryContainer, "theme-on-secondary-container"),
    tertiary = animatedThemeColor(target.tertiary, "theme-tertiary"),
    onTertiary = animatedThemeColor(target.onTertiary, "theme-on-tertiary"),
    tertiaryContainer = animatedThemeColor(target.tertiaryContainer, "theme-tertiary-container"),
    onTertiaryContainer = animatedThemeColor(target.onTertiaryContainer, "theme-on-tertiary-container"),
    background = animatedThemeColor(target.background, "theme-background"),
    onBackground = animatedThemeColor(target.onBackground, "theme-on-background"),
    surface = animatedThemeColor(target.surface, "theme-surface"),
    onSurface = animatedThemeColor(target.onSurface, "theme-on-surface"),
    surfaceVariant = animatedThemeColor(target.surfaceVariant, "theme-surface-variant"),
    onSurfaceVariant = animatedThemeColor(target.onSurfaceVariant, "theme-on-surface-variant"),
    surfaceTint = animatedThemeColor(target.surfaceTint, "theme-surface-tint"),
    inverseSurface = animatedThemeColor(target.inverseSurface, "theme-inverse-surface"),
    inverseOnSurface = animatedThemeColor(target.inverseOnSurface, "theme-inverse-on-surface"),
    error = animatedThemeColor(target.error, "theme-error"),
    onError = animatedThemeColor(target.onError, "theme-on-error"),
    errorContainer = animatedThemeColor(target.errorContainer, "theme-error-container"),
    onErrorContainer = animatedThemeColor(target.onErrorContainer, "theme-on-error-container"),
    outline = animatedThemeColor(target.outline, "theme-outline"),
    outlineVariant = animatedThemeColor(target.outlineVariant, "theme-outline-variant"),
    scrim = animatedThemeColor(target.scrim, "theme-scrim"),
    surfaceBright = animatedThemeColor(target.surfaceBright, "theme-surface-bright"),
    surfaceDim = animatedThemeColor(target.surfaceDim, "theme-surface-dim"),
    surfaceContainer = animatedThemeColor(target.surfaceContainer, "theme-surface-container"),
    surfaceContainerHigh = animatedThemeColor(target.surfaceContainerHigh, "theme-surface-container-high"),
    surfaceContainerHighest = animatedThemeColor(target.surfaceContainerHighest, "theme-surface-container-highest"),
    surfaceContainerLow = animatedThemeColor(target.surfaceContainerLow, "theme-surface-container-low"),
    surfaceContainerLowest = animatedThemeColor(target.surfaceContainerLowest, "theme-surface-container-lowest"),
)

@Composable
fun LazerTheme(
    isDark: Boolean,
    colorScheme: ColorScheme? = null,
    engine: LazerThemeEngine = LazerThemeEngine.MATERIAL3,
    content: @Composable () -> Unit,
) {
    val targetColorScheme = colorScheme ?: if (isDark) DarkColors else LightColors
    val animatedColorScheme = rememberAnimatedColorScheme(targetColorScheme)
    CompositionLocalProvider(LocalLazerThemeEngine provides engine) {
        if (engine == LazerThemeEngine.MIUIX) {
            val miuixColors = animatedColorScheme.toMiuixColors(isDark)
            MiuixTheme(
                colors = miuixColors,
                smoothRounding = true,
            ) {
                val miuixTextStyles = MiuixTheme.textStyles
                val materialTypography = remember(miuixTextStyles) {
                    miuixTextStyles.toMaterial3Typography()
                }
                LazerMaterialTheme(
                    colorScheme = miuixColors.toMaterial3ColorScheme(isDark),
                    typography = materialTypography,
                    shapes = MiuixMaterialShapes,
                    content = content,
                )
            }
        } else {
            LazerMaterialTheme(
                colorScheme = animatedColorScheme,
                shapes = Shapes(),
                content = content,
            )
        }
    }
}

@Composable
private fun LazerMaterialTheme(
    colorScheme: ColorScheme,
    typography: Typography = LazerTypography,
    shapes: Shapes,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        shapes = shapes,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides colorScheme.onBackground,
            LocalRippleConfiguration provides RippleConfiguration(color = colorScheme.primary),
            content = content,
        )
    }
}

private fun MiuixTextStyles.toMaterial3Typography(): Typography = Typography(
    displaySmall = title1.copy(fontWeight = FontWeight.SemiBold),
    headlineMedium = title2.copy(fontWeight = FontWeight.SemiBold),
    headlineSmall = title3.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = title4.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = headline1.copy(fontWeight = FontWeight.Medium),
    titleSmall = subtitle,
    bodyLarge = paragraph,
    bodyMedium = body1,
    bodySmall = body2,
    labelLarge = button.copy(fontWeight = FontWeight.Medium),
    labelMedium = footnote1.copy(fontWeight = FontWeight.Medium),
    labelSmall = footnote2,
)

private fun ColorScheme.toMiuixColors(isDark: Boolean): MiuixColors {
    val base = if (isDark) miuixDarkColorScheme() else miuixLightColorScheme()
    return base.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryVariant = primary,
        onPrimaryVariant = onPrimary,
        error = error,
        onError = onError,
        errorContainer = errorContainer,
        onErrorContainer = onErrorContainer,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryVariant = secondaryContainer,
        onSecondaryVariant = onSecondaryContainer,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        secondaryContainerVariant = surfaceContainerHigh,
        onSecondaryContainerVariant = onSurfaceVariant,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        tertiaryContainerVariant = surfaceContainerHigh,
        background = background,
        onBackground = onBackground,
        onBackgroundVariant = onSurfaceVariant,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceSecondary = onSurface.copy(alpha = 0.8f),
        onSurfaceVariantSummary = onSurfaceVariant,
        onSurfaceVariantActions = onSurfaceVariant.copy(alpha = 0.72f),
        surfaceContainer = surfaceContainer,
        onSurfaceContainer = onSurface,
        onSurfaceContainerVariant = onSurfaceVariant,
        surfaceContainerHigh = surfaceContainerHigh,
        onSurfaceContainerHigh = onSurfaceVariant,
        surfaceContainerHighest = surfaceContainerHighest,
        onSurfaceContainerHighest = onSurface,
        outline = outline,
        dividerLine = outlineVariant,
        sliderBackground = onSurface.copy(alpha = if (isDark) 0.15f else 0.08f),
    )
}

private fun MiuixColors.toMaterial3ColorScheme(isDark: Boolean): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = secondaryVariant,
        onSecondary = onSecondaryVariant,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = primaryVariant,
        onTertiary = onPrimary,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariantSummary,
        surfaceContainer = surfaceContainer,
        surfaceContainerLow = surface,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest,
        outline = outline,
        outlineVariant = dividerLine,
        error = error,
        onError = onError,
        errorContainer = errorContainer,
        onErrorContainer = onErrorContainer,
    )
}
