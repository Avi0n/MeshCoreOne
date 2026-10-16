import Foundation

extension String {
  /// Spoken VoiceOver name for an emoji, derived from its Unicode character name
  /// (e.g. "👍" becomes "thumbs up sign"). Reaction controls use this so they
  /// announce a stable, app-controlled label rather than relying on VoiceOver's
  /// built-in emoji pronunciation, which varies by locale and OS version.
  var emojiAccessibilityName: String {
    let cfstr = NSMutableString(string: self) as CFMutableString
    CFStringTransform(cfstr, nil, kCFStringTransformToUnicodeName, false)
    let name = cfstr as String
    return name.replacing("\\N{", with: "").replacing("}", with: "").lowercased()
  }
}
