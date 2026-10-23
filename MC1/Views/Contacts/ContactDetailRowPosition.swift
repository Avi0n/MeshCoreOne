import SwiftUI

struct ContactDetailRowPosition: ViewModifier {
  struct Measurement: Equatable {
    let id: UUID
    let section: ContactDetailState.ScrollSection
    let frame: CGRect?
  }

  let section: ContactDetailState.ScrollSection
  let onPosition: ((Measurement) -> Void)?
  @State private var id = UUID()
  @State private var frame: CGRect?
  @State private var isMounted = false

  func body(content: Content) -> some View {
    if let onPosition {
      ForEach([section], id: \.self) { _ in
        content
          .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { value in
            frame = value
            if isMounted { onPosition(Measurement(id: id, section: section, frame: value)) }
          }
          .onAppear {
            isMounted = true
            if let frame { onPosition(Measurement(id: id, section: section, frame: frame)) }
          }
          .onDisappear {
            isMounted = false
            onPosition(Measurement(id: id, section: section, frame: nil))
          }
      }
    } else {
      content
    }
  }
}
