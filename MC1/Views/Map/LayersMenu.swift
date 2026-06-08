import SwiftUI

struct LayersMenu: View {
    @Binding var selection: MapStyleSelection
    @Binding var isPresented: Bool

    var body: some View {
        VStack(spacing: 0) {
            ForEach(MapStyleSelection.allCases, id: \.self) { style in
                Button {
                    selection = style
                    withAnimation {
                        isPresented = false
                    }
                } label: {
                    HStack {
                        Text(style.label)
                        Spacer()
                        if selection == style {
                            Image(systemName: "checkmark")
                                .foregroundStyle(.tint)
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                }
                .foregroundStyle(.primary)

                if style != MapStyleSelection.allCases.last {
                    Divider()
                }
            }
        }
        .frame(width: 140)
        .liquidGlass(in: .rect(cornerRadius: 12))
        .shadow(color: .black.opacity(0.2), radius: 8, y: 4)
        .accessibilityElement(children: .contain)
        .accessibilityLabel(L10n.Map.Map.Style.accessibilityLabel)
    }
}

#Preview {
    LayersMenu(
        selection: .constant(.standard),
        isPresented: .constant(true)
    )
    .padding()
}
