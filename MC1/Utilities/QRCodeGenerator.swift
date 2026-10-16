import CoreImage.CIFilterBuiltins
import UIKit

/// Generates QR codes whose data modules are opaque and background is transparent, so the image
/// can be rendered as a `.template` and tinted with the current text color while the cell/canvas
/// shows through the background. This keeps QR codes legible in both light and dark mode.
enum QRCodeGenerator {
  private static let context = CIContext()

  static func generate(from string: String, scale: CGFloat = 10.0, correctionLevel: String = "M") -> UIImage? {
    let filter = CIFilter.qrCodeGenerator()
    filter.message = Data(string.utf8)
    filter.correctionLevel = correctionLevel

    guard let qrImage = filter.outputImage else { return nil }

    let colorFilter = CIFilter.falseColor()
    colorFilter.inputImage = qrImage
    colorFilter.color0 = CIColor.black
    colorFilter.color1 = CIColor(red: 0, green: 0, blue: 0, alpha: 0)

    guard let coloredImage = colorFilter.outputImage else { return nil }

    let scaledImage = coloredImage.transformed(by: CGAffineTransform(scaleX: scale, y: scale))

    guard let cgImage = context.createCGImage(scaledImage, from: scaledImage.extent) else {
      return nil
    }

    return UIImage(cgImage: cgImage).withRenderingMode(.alwaysTemplate)
  }
}
