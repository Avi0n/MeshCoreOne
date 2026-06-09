import SwiftUI

/// Places a static section background behind an iPad sidebar content column and extends it under
/// the floating Liquid Glass sidebar on iOS 26+ via `sidebarBackgroundExtension()`.
///
/// Only themes with a surface canvas paint a flat background here, and the effect is applied to
/// that static color (not the scrolling list), so only a single flat surface is routed through the
/// GPU duplicate-mirror-blur. Surface-less themes (Default) leave the system list background
/// untouched to preserve iOS 26 List Liquid Glass, matching `ThemedCanvasModifier`'s deliberate
/// no-op; the floating sidebar simply overlays the unmodified system surface there. The leading
/// inset keeps row content clear of the floating glass in both cases.
struct SidebarContentColumnBackground: ViewModifier {
    let theme: Theme

    @ViewBuilder
    func body(content: Content) -> some View {
        if let canvas = theme.surfaces?.canvas {
            content
                .scrollContentBackground(.hidden)
                .safeAreaPadding(.leading, sidebarContentLeadingInset)
                .background {
                    canvas
                        .ignoresSafeArea()
                        .sidebarBackgroundExtension()
                }
        } else {
            content
                .safeAreaPadding(.leading, sidebarContentLeadingInset)
        }
    }
}
