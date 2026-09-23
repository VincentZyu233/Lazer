package dev.naominet.lazer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/** Desktop adapter: pause the shared renderer when the window is not foregrounded. */
@Composable
internal fun AlbumFlowBackground(
    colors: List<Color>,
    modifier: Modifier = Modifier,
    cornerRadius: Dp,
    veil: Color,
    animated: Boolean = true,
    solid: Boolean = false,
) {
    LazerAlbumFlowBackground(
        colors = colors,
        modifier = modifier,
        cornerRadius = cornerRadius,
        veil = veil,
        animated = animated && LocalDesktopWindowForeground.current,
        paletteAnimated = LocalDesktopWindowForeground.current,
        solid = solid,
    )
}
