import SwiftUI

struct BatchSizeChip: View {
    let size: Int
    @Binding var selectedSize: Int

    private var isSelected: Bool { selectedSize == size }

    var body: some View {
        Button {
            selectedSize = size
        } label: {
            Text("\(size)×")
                .font(.subheadline.weight(.medium))
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(isSelected ? Color.accentColor : Color.secondary.opacity(0.2), in: .capsule)
                .foregroundStyle(isSelected ? .white : .primary)
        }
        .buttonStyle(.plain)
    }
}
