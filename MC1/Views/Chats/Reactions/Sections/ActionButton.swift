import SwiftUI

struct ActionButton: View {
    let title: String
    let icon: String
    var isDestructive: Bool = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack {
                Label(title, systemImage: icon)
                Spacer()
            }
            .padding()
            .contentShape(.rect)
        }
        .foregroundStyle(isDestructive ? .red : .primary)
    }
}
