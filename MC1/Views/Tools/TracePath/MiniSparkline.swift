import SwiftUI

struct MiniSparkline: View {
    let values: [Int]

    var body: some View {
        GeometryReader { geometry in
            if let minVal = values.min(), let maxVal = values.max(), maxVal > minVal {
                Path { path in
                    let range = Double(maxVal - minVal)
                    let stepX = geometry.size.width / Double(max(values.count - 1, 1))

                    for (index, value) in values.enumerated() {
                        let x = Double(index) * stepX
                        let y = geometry.size.height - (Double(value - minVal) / range * geometry.size.height)

                        if index == 0 {
                            path.move(to: CGPoint(x: x, y: y))
                        } else {
                            path.addLine(to: CGPoint(x: x, y: y))
                        }
                    }
                }
                .stroke(Color.accentColor, lineWidth: 1.5)
            } else {
                // Flat line for constant values
                Path { path in
                    path.move(to: CGPoint(x: 0, y: geometry.size.height / 2))
                    path.addLine(to: CGPoint(x: geometry.size.width, y: geometry.size.height / 2))
                }
                .stroke(Color.accentColor, lineWidth: 1.5)
            }
        }
    }
}
