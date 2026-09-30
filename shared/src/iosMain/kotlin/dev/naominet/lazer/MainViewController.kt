package dev.naominet.lazer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIViewController

private const val IOS_COMPOSE_READY_MARKER = "LAZER_IOS_COMPOSE_READY"
private const val IOS_ROUTE_ARGUMENT = "LAZER_IOS_ROUTE="
private var didReportIOSComposeFrame = false

/**
 * The launch smoke test walks the screens one at a time, because a screenshot is the only view of
 * this app that CI can offer. The route arrives as a launch argument and the shared screens take
 * them from there, through the same navigation calls a tap makes.
 */
@OptIn(ExperimentalForeignApi::class)
private fun iosSmokeRoute(): String? = NSProcessInfo.processInfo.arguments
    .asSequence()
    .map { it.toString() }
    .firstOrNull { it.startsWith(IOS_ROUTE_ARGUMENT) }
    ?.removePrefix(IOS_ROUTE_ARGUMENT)
    ?.takeIf(String::isNotBlank)

/**
 * The UIKit host for the shared Compose interface - the same screens Android draws.
 *
 * Swift hands over a bridge for the few sheets only a native caller can present: the camera, the
 * photo picker, the share panel and the sign-in browser. Everything around them is shared code.
 */
fun MainViewController(bridge: IosShellBridge): UIViewController = ComposeUIViewController {
    val controller = remember(bridge) { LazerGatewayController(iosDevice(bridge)) }
    val screen = remember(bridge) { IosScreenHost(bridge) }
    val route = remember { iosSmokeRoute() }
    // The launch smoke test needs proof a frame reached the screen, not merely that composition ran.
    Box(
        Modifier
            .fillMaxSize()
            .drawWithContent {
                drawContent()
                if (!didReportIOSComposeFrame) {
                    didReportIOSComposeFrame = true
                    println(IOS_COMPOSE_READY_MARKER)
                }
            },
    ) {
        LazerApp(
            controller = controller,
            screen = screen,
            smokeRoute = route,
            authWebView = { url, sessionCookie, modifier ->
                androidx.compose.ui.viewinterop.UIKitView(
                    factory = { bridge.makeAuthWebView(url, sessionCookie) },
                    modifier = modifier,
                )
            },
        )
    }
}
