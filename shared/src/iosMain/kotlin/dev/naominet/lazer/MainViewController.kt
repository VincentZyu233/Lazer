package dev.naominet.lazer

import androidx.compose.ui.window.ComposeUIViewController

/** UIKit host for the shared Compose UI. SwiftUI only layers native Liquid Glass above this view. */
fun MainViewController() = ComposeUIViewController { IOSLazerApp() }
