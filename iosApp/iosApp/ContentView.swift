import SwiftUI
import Shared
import UIKit

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    @AppStorage("lazer.ios.style") private var style = "MATERIAL"
    @AppStorage("lazer.ios.nativeLiquidGlass") private var nativeLiquidGlass = false
    @AppStorage("lazer.ios.destination") private var destination = "HOME"

    private var showsNativeGlass: Bool {
        style == "LIQUID_GLASS" && nativeLiquidGlass
    }

    var body: some View {
        ZStack(alignment: .bottom) {
            ComposeView()
                .ignoresSafeArea()

            if showsNativeGlass {
                NativeLiquidGlassNavigation(selection: $destination)
                    .padding(.horizontal, 16)
                    .padding(.bottom, 8)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.smooth(duration: 0.28), value: showsNativeGlass)
    }
}

private struct NativeLiquidGlassNavigation: View {
    private struct Destination: Identifiable {
        let id: String
        let icon: String
        let label: String
    }

    @Binding var selection: String

    private let destinations = [
        Destination(id: "HOME", icon: "house", label: "主页"),
        Destination(id: "SEARCH", icon: "magnifyingglass", label: "搜索"),
        Destination(id: "LIBRARY", icon: "music.note.list", label: "音乐库"),
        Destination(id: "SETTINGS", icon: "gearshape", label: "设置"),
    ]

    var body: some View {
        Group {
            if #available(iOS 26.0, *) {
                GlassEffectContainer(spacing: 8) {
                    navigationContent
                        .padding(6)
                        .glassEffect(.regular.interactive())
                }
            } else {
                navigationContent
                    .padding(6)
                    .background(.ultraThinMaterial, in: Capsule())
                    .overlay {
                        Capsule().stroke(.white.opacity(0.18), lineWidth: 0.5)
                    }
            }
        }
        .frame(maxWidth: 640)
        .shadow(color: .black.opacity(0.12), radius: 16, y: 8)
        .accessibilityElement(children: .contain)
        .accessibilityLabel("主导航")
    }

    private var navigationContent: some View {
        HStack(spacing: 2) {
            ForEach(destinations) { destination in
                let selected = selection == destination.id
                Button {
                    selection = destination.id
                } label: {
                    VStack(spacing: 3) {
                        Image(systemName: destination.icon)
                            .font(.system(size: 17, weight: .semibold))
                            .frame(height: 20)
                        Text(destination.label)
                            .font(.caption2.weight(selected ? .semibold : .regular))
                            .lineLimit(1)
                    }
                    .foregroundStyle(selected ? Color.accentColor : Color.secondary)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 7)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityValue(selected ? "已选择" : "")
            }
        }
    }
}
