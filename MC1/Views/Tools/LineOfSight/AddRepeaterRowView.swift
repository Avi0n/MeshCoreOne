import SwiftUI

struct AddRepeaterRowView: View {
    let onAdd: () -> Void

    var body: some View {
        Button {
            onAdd()
        } label: {
            HStack {
                // Purple R marker (matches full row)
                Circle()
                    .fill(.purple)
                    .frame(width: 24, height: 24)
                    .overlay {
                        Text("R")
                            .font(.caption)
                            .bold()
                            .foregroundStyle(.white)
                    }

                Text(L10n.Tools.Tools.LineOfSight.addRepeater)
                    .font(.subheadline)

                Spacer()

                Image(systemName: "plus.circle.fill")
                    .foregroundStyle(.purple)
            }
            .padding(.vertical, 8)
        }
        .liquidGlassSecondaryButtonStyle()
    }
}
