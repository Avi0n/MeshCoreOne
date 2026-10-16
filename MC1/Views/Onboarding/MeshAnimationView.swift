import SwiftUI

/// Animated mesh network visualization showing interconnected nodes with a traveling message.
struct MeshAnimationView: View {
  @Environment(\.accessibilityReduceMotion) private var reduceMotion

  private struct Node: Identifiable {
    let id = UUID()
    var position: CGPoint
    let isUserNode: Bool
  }

  private struct Edge: Identifiable {
    let id = UUID()
    let from: Int
    let to: Int
  }

  // MARK: - Animation Constants

  private let nodeRadius: CGFloat = 8
  private let userNodeRadius: CGFloat = 12
  private let messageRadius: CGFloat = 4
  private let edgeLineWidth: CGFloat = 1.5

  private let edgeFadeFrequency: Double = 0.8
  private let edgeMinOpacity: Double = 0.3
  private let edgeFadeAmplitude: Double = 0.3
  private let edgePhaseOffset: Double = 0.7

  private let nodePulseFrequency: Double = 1.2
  private let nodePulseAmplitude: Double = 0.15
  private let nodePhaseOffset: Double = 0.5

  private let messageCycleDuration: Double = 4.0

  private let nodes: [Node] = [
    Node(position: CGPoint(x: 0.2, y: 0.3), isUserNode: false),
    Node(position: CGPoint(x: 0.5, y: 0.15), isUserNode: false),
    Node(position: CGPoint(x: 0.8, y: 0.25), isUserNode: false),
    Node(position: CGPoint(x: 0.15, y: 0.7), isUserNode: false),
    Node(position: CGPoint(x: 0.5, y: 0.55), isUserNode: true),
    Node(position: CGPoint(x: 0.85, y: 0.75), isUserNode: false),
  ]

  private let edges: [Edge] = [
    Edge(from: 0, to: 1),
    Edge(from: 1, to: 2),
    Edge(from: 0, to: 4),
    Edge(from: 1, to: 4),
    Edge(from: 3, to: 4),
    Edge(from: 4, to: 5),
    Edge(from: 2, to: 5),
  ]

  private let messagePath: [Int] = [3, 4, 1, 2]

  var body: some View {
    Group {
      if reduceMotion {
        Canvas { context, size in
          drawMesh(context: context, size: size, time: 0)
        }
      } else {
        TimelineView(.animation(minimumInterval: 1 / 30)) { timeline in
          Canvas { context, size in
            let time = timeline.date.timeIntervalSinceReferenceDate
            drawMesh(context: context, size: size, time: time)
          }
        }
      }
    }
    .frame(height: 150)
    .accessibilityLabel(L10n.Onboarding.MeshAnimation.accessibilityLabel)
    .accessibilityAddTraits(.isImage)
  }

  private func drawMesh(context: GraphicsContext, size: CGSize, time: Double) {
    // Draw edges with fading effect
    for (index, edge) in edges.enumerated() {
      let fromNode = nodes[edge.from]
      let toNode = nodes[edge.to]

      let fromPoint = CGPoint(
        x: fromNode.position.x * size.width,
        y: fromNode.position.y * size.height
      )
      let toPoint = CGPoint(
        x: toNode.position.x * size.width,
        y: toNode.position.y * size.height
      )

      let phase = Double(index) * edgePhaseOffset
      let opacity = edgeMinOpacity + edgeFadeAmplitude * sin(time * edgeFadeFrequency + phase)

      var path = Path()
      path.move(to: fromPoint)
      path.addLine(to: toPoint)

      context.stroke(
        path,
        with: .color(Color.accentColor.opacity(opacity)),
        lineWidth: edgeLineWidth
      )
    }

    // Draw nodes with pulse effect
    for (index, node) in nodes.enumerated() {
      let center = CGPoint(
        x: node.position.x * size.width,
        y: node.position.y * size.height
      )

      let phase = Double(index) * nodePhaseOffset
      let scale = 1.0 + nodePulseAmplitude * sin(time * nodePulseFrequency + phase)
      let baseRadius = node.isUserNode ? userNodeRadius : nodeRadius
      let radius = baseRadius * scale

      let rect = CGRect(
        x: center.x - radius,
        y: center.y - radius,
        width: radius * 2,
        height: radius * 2
      )

      context.fill(
        Path(ellipseIn: rect),
        with: .color(Color.accentColor.opacity(node.isUserNode ? 1.0 : 0.7))
      )
    }

    // Draw traveling message
    let progress = (time.truncatingRemainder(dividingBy: messageCycleDuration)) / messageCycleDuration
    let segmentCount = messagePath.count - 1
    let totalProgress = progress * Double(segmentCount)
    let segmentIndex = min(Int(totalProgress), segmentCount - 1)
    let segmentProgress = totalProgress - Double(segmentIndex)

    if segmentIndex < segmentCount {
      let fromNode = nodes[messagePath[segmentIndex]]
      let toNode = nodes[messagePath[segmentIndex + 1]]

      let fromPoint = CGPoint(
        x: fromNode.position.x * size.width,
        y: fromNode.position.y * size.height
      )
      let toPoint = CGPoint(
        x: toNode.position.x * size.width,
        y: toNode.position.y * size.height
      )

      let messageX = fromPoint.x + (toPoint.x - fromPoint.x) * segmentProgress
      let messageY = fromPoint.y + (toPoint.y - fromPoint.y) * segmentProgress

      let messageRect = CGRect(
        x: messageX - messageRadius,
        y: messageY - messageRadius,
        width: messageRadius * 2,
        height: messageRadius * 2
      )

      context.fill(
        Path(ellipseIn: messageRect),
        with: .color(Color.accentColor)
      )
    }
  }
}

#Preview {
  MeshAnimationView()
    .padding()
    .background(.black.opacity(0.1))
}
