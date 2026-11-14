import MC1Services
import Testing
@testable import MC1

@Suite("Pixel paint drafts")
@MainActor
struct PixelPaintDraftTests {
  @Test func paintingInterpolatesAndRetainsPixelsAcrossEditorSessions() {
    let draft = PixelPaintDraft()
    draft.paint(x: 0, y: 0)
    draft.paint(x: PixelPaintDraft.side - 1, y: PixelPaintDraft.side - 1)
    draft.endStroke()
    draft.isStaged = true
    let staged = draft.image
    for index in 0..<PixelPaintDraft.side {
      #expect(staged.pixels[index * PixelPaintDraft.side + index] == PixelPaintDraft.initialBrush)
    }
    draft.endStroke()
    #expect(draft.image == staged)
    #expect(draft.isStaged)
  }

  @Test func admissionDoesNotPermitAnotherStrokeOrErasePixelsOnFailure() {
    let draft = PixelPaintDraft()
    draft.paint(x: 1, y: 2)
    draft.endStroke()
    draft.isStaged = true
    let image = draft.image
    draft.isSubmitting = true
    draft.paint(x: 3, y: 4)
    draft.isSubmitting = false
    #expect(draft.image == image)
    #expect(draft.isStaged)
    draft.remove()
    #expect(!draft.isStaged)
    #expect(draft.image.pixels.allSatisfy { $0 == PixelPaintDraft.background })
  }

  @Test func canvasEdgesDoNotWrapIntoOtherRows() {
    let draft = PixelPaintDraft()
    draft.paint(x: PixelPaintDraft.side, y: 0)
    draft.paint(x: -1, y: 0)
    draft.paint(x: 0, y: PixelPaintDraft.side)
    #expect(draft.image.pixels.allSatisfy { $0 == PixelPaintDraft.background })
  }
}
