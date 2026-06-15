import SwiftUI

/// Installs the bubble's press gestures only when VoiceOver is off. Under
/// VoiceOver a custom press recognizer never fires (the system owns touches), so
/// the actions sheet and any link are reached through accessibility actions.
///
/// The long-press drives the press-in lift that gates the actions sheet, a
/// secondary click opens the sheet directly for pointer users, and the tap opens
/// a URL-only message's link (inert when `soleURL` is nil).
struct BubbleGestureModifier: ViewModifier {
    let isVoiceOver: Bool
    let soleURL: URL?
    let onPressChanged: (Bool) -> Void
    let onOpenURL: (URL) -> Void
    let onSecondaryClick: () -> Void

    func body(content: Content) -> some View {
        if isVoiceOver {
            content
        } else {
            content
                .gesture(BubbleLongPressGesture(onPressChanged: onPressChanged))
                .gesture(BubbleSecondaryClickGesture(onSecondaryClick: onSecondaryClick))
                .gesture(BubbleTapGesture(url: soleURL, onTap: onOpenURL))
        }
    }
}
