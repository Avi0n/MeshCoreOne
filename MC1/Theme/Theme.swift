import MC1Services
import SwiftUI

/// A cosmetic theme: accent + chat colors + optional surfaces + optional forced color scheme.
/// Built-in themes are static factory values (below), not subtypes.
struct Theme: Identifiable, Equatable {
  let id: String
  /// SwiftGen key path (`Settings.Support.Theme.X`) for themes whose name is localized. `nil` for
  /// proper-noun themes (Sakura, Solarized, Nord, Catppuccin) that are never translated and resolve
  /// their fixed name from the explicit `localizedName` switch instead.
  let displayNameKey: String?
  let productID: String?
  let accentColor: Color
  let outgoingTextColor: Color
  let hashtagColor: Color
  let preferredColorScheme: ColorScheme?
  let surfaces: Surfaces?
  /// The theme's identity-color space for contact avatars, channel sender names, and mentions.
  /// Varied per identity, drawn from the theme's character hues.
  let identityGamut: IdentityGamut
  /// Set only by the System theme to pin channel / repeater / room avatars to fixed legacy colors.
  /// `nil` for every other theme, which derives its category colors from `identityGamut`.
  let categoryAvatarOverride: CategoryAvatarColors?
  /// Curated channel / repeater / room avatar hues for a gamut theme, each picked from the theme's
  /// anchors to be on-palette and distinct. `nil` falls back to a distinct auto-anchor pick.
  let categoryHues: CategoryHues?

  init(
    id: String,
    displayNameKey: String?,
    productID: String?,
    accentColor: Color,
    outgoingTextColor: Color,
    hashtagColor: Color,
    preferredColorScheme: ColorScheme?,
    surfaces: Surfaces? = nil,
    identityGamut: IdentityGamut,
    categoryAvatarOverride: CategoryAvatarColors? = nil,
    categoryHues: CategoryHues? = nil
  ) {
    self.id = id
    self.displayNameKey = displayNameKey
    self.productID = productID
    self.accentColor = accentColor
    self.outgoingTextColor = outgoingTextColor
    self.hashtagColor = hashtagColor
    self.preferredColorScheme = preferredColorScheme
    self.surfaces = surfaces
    self.identityGamut = identityGamut
    self.categoryAvatarOverride = categoryAvatarOverride
    self.categoryHues = categoryHues
  }
}

extension Theme {
  /// Canvas (system grouped replacement) + card (secondary system grouped replacement) for paid themes.
  /// `card == nil` means "paint the canvas but leave card rows on the system tier" (Ember).
  struct Surfaces: Equatable {
    let canvas: Color
    let card: Color?

    init(canvas: Color, card: Color? = nil) {
      self.canvas = canvas
      self.card = card
    }

    /// Fill for a card-tier list row. Returns `nil` when `flatten` is set (the iPad Settings
    /// sidebar column), so rows stay transparent: the column canvas shows through and the native
    /// `.sidebar` selection highlight survives, which any `listRowBackground` would suppress.
    /// Otherwise returns the card tier — the inset-grouped "card on grouped gray" look used
    /// everywhere else. `nil` for surfaces without a card tier (Ember).
    func rowFill(flatten: Bool) -> Color? {
      flatten ? nil : card
    }
  }

  /// Tint to impose on global chrome (buttons, links, controls) at the scene root.
  /// The default theme returns `nil` so chrome defers to the system tint; `accentColor`
  /// is reserved for deliberate brand surfaces (chat bubbles, palette swatch).
  var chromeTint: Color? {
    id == Theme.default.id ? nil : accentColor
  }

  /// Incoming message bubble fill. Themed surfaces use the card tier, which is tuned to
  /// contrast with the canvas; the default theme (and canvas-only themes like Ember) keep
  /// the system gray that reads correctly on `systemBackground`.
  var incomingBubbleColor: Color {
    surfaces?.card ?? AppColors.Message.incomingBubble
  }
}

extension Theme {
  static let `default` = Theme(
    id: EnvInputs.defaultThemeID,
    displayNameKey: "Settings.Support.Theme.Default",
    productID: nil,
    accentColor: Color("AppAccentColor"),
    outgoingTextColor: .white,
    hashtagColor: Color("HashtagDefault"),
    preferredColorScheme: nil,
    identityGamut: IdentityGamut(
      hueAnchors: [18, 25, 44, 77, 120, 180, 215, 255, 307, 343],
      saturation: 0.45...0.70
    ),
    categoryAvatarOverride: CategoryAvatarColors(
      channel: Color(hex: 0x336688),
      repeaterNode: Color(hex: 0x00AAFF),
      room: Color(hex: 0xFF8800)
    )
  )

  static let ember = Theme(
    id: "ember",
    displayNameKey: "Settings.Support.Theme.Ember",
    productID: StoreCatalog.Theme.ember,
    accentColor: Color("Theme/Ember/Accent"),
    outgoingTextColor: Color("Theme/Ember/Text"),
    hashtagColor: Color("Theme/Ember/Hashtag"),
    preferredColorScheme: .dark,
    surfaces: .init(canvas: .black),
    identityGamut: IdentityGamut(
      hueAnchors: [0, 8, 12, 24, 18, 345],
      saturation: 0.50...0.82
    ),
    categoryHues: CategoryHues(channel: 0, repeater: 24, room: 345)
  )

  static let fern = Theme(
    id: "fern",
    displayNameKey: "Settings.Support.Theme.Fern",
    productID: StoreCatalog.Theme.fern,
    accentColor: Color("Theme/Fern/Accent"),
    outgoingTextColor: Color("Theme/Fern/OutgoingText"),
    hashtagColor: Color("Theme/Fern/Hashtag"),
    preferredColorScheme: nil,
    surfaces: .init(canvas: Color("Theme/Fern/Canvas"), card: Color("Theme/Fern/Card")),
    identityGamut: IdentityGamut(
      hueAnchors: [72, 90, 108, 128, 150, 165],
      saturation: 0.35...0.65
    ),
    categoryHues: CategoryHues(channel: 90, repeater: 128, room: 165)
  )

  static let marine = Theme(
    id: "marine",
    displayNameKey: "Settings.Support.Theme.Marine",
    productID: StoreCatalog.Theme.marine,
    accentColor: Color("Theme/Marine/Accent"),
    outgoingTextColor: .white,
    hashtagColor: Color("Theme/Marine/Hashtag"),
    preferredColorScheme: nil,
    surfaces: .init(canvas: Color("Theme/Marine/Canvas"), card: Color("Theme/Marine/Card")),
    identityGamut: IdentityGamut(
      hueAnchors: [178, 188, 200, 215, 232, 245],
      saturation: 0.40...0.72
    ),
    categoryHues: CategoryHues(channel: 215, repeater: 188, room: 245)
  )

  static let olive = Theme(
    id: "olive",
    displayNameKey: "Settings.Support.Theme.Olive",
    productID: StoreCatalog.Theme.olive,
    accentColor: Color("Theme/Olive/Accent"),
    outgoingTextColor: Color("Theme/Olive/OutgoingText"),
    hashtagColor: Color("Theme/Olive/Hashtag"),
    preferredColorScheme: nil,
    surfaces: .init(canvas: Color("Theme/Olive/Canvas"), card: Color("Theme/Olive/Card")),
    identityGamut: IdentityGamut(
      hueAnchors: [45, 55, 70, 85, 100, 112],
      saturation: 0.35...0.62
    ),
    categoryHues: CategoryHues(channel: 85, repeater: 112, room: 70)
  )

  static let lavender = Theme(
    id: "lavender",
    displayNameKey: "Settings.Support.Theme.Lavender",
    productID: StoreCatalog.Theme.lavender,
    accentColor: Color("Theme/Lavender/Accent"),
    outgoingTextColor: Color("Theme/Lavender/OutgoingText"),
    hashtagColor: Color("Theme/Lavender/Hashtag"),
    preferredColorScheme: nil,
    surfaces: .init(canvas: Color("Theme/Lavender/Canvas"), card: Color("Theme/Lavender/Card")),
    identityGamut: IdentityGamut(
      hueAnchors: [222, 238, 248, 265, 285, 302],
      saturation: 0.35...0.62
    ),
    categoryHues: CategoryHues(channel: 265, repeater: 222, room: 302)
  )

  static let sakura = Theme(
    id: "sakura",
    displayNameKey: nil,
    productID: StoreCatalog.Theme.sakura,
    accentColor: Color("Theme/Sakura/Accent"),
    outgoingTextColor: Color("Theme/Sakura/OutgoingText"),
    hashtagColor: Color("Theme/Sakura/Hashtag"),
    preferredColorScheme: nil,
    surfaces: .init(canvas: Color("Theme/Sakura/Canvas"), card: Color("Theme/Sakura/Card")),
    identityGamut: IdentityGamut(
      hueAnchors: [290, 305, 320, 335, 350, 8],
      saturation: 0.40...0.70
    ),
    categoryHues: CategoryHues(channel: 320, repeater: 290, room: 350)
  )

  static let solarized = Theme(
    id: "solarized",
    displayNameKey: nil,
    productID: StoreCatalog.Theme.solarized,
    accentColor: Color("Theme/Solarized/Accent"),
    outgoingTextColor: Color("Theme/Solarized/OutgoingText"),
    hashtagColor: Color("Theme/Solarized/Hashtag"),
    preferredColorScheme: nil,
    surfaces: .init(canvas: Color("Theme/Solarized/Canvas"), card: Color("Theme/Solarized/Card")),
    identityGamut: IdentityGamut(
      hueAnchors: [1, 18, 45, 68, 175, 205, 237, 331],
      saturation: 0.50...0.80
    ),
    categoryHues: CategoryHues(channel: 205, repeater: 175, room: 331)
  )

  static let nord = Theme(
    id: "nord",
    displayNameKey: nil,
    productID: StoreCatalog.Theme.nord,
    accentColor: Color("Theme/Nord/Accent"),
    outgoingTextColor: Color("Theme/Nord/OutgoingText"),
    hashtagColor: Color("Theme/Nord/Hashtag"),
    preferredColorScheme: nil,
    surfaces: .init(canvas: Color("Theme/Nord/Canvas"), card: Color("Theme/Nord/Card")),
    identityGamut: IdentityGamut(
      hueAnchors: [14, 40, 92, 178, 193, 210, 213, 240, 280, 311, 354],
      saturation: 0.25...0.52
    ),
    categoryHues: CategoryHues(channel: 210, repeater: 193, room: 280)
  )

  static let catppuccin = Theme(
    id: "catppuccin",
    displayNameKey: nil,
    productID: StoreCatalog.Theme.catppuccin,
    accentColor: Color("Theme/Catppuccin/Accent"),
    outgoingTextColor: Color("Theme/Catppuccin/OutgoingText"),
    hashtagColor: Color("Theme/Catppuccin/Hashtag"),
    preferredColorScheme: nil,
    surfaces: .init(canvas: Color("Theme/Catppuccin/Canvas"), card: Color("Theme/Catppuccin/Card")),
    identityGamut: IdentityGamut(
      hueAnchors: [0, 10, 23, 41, 115, 170, 189, 199, 217, 232, 267, 316, 343, 351],
      saturation: 0.40...0.70
    ),
    categoryHues: CategoryHues(channel: 267, repeater: 217, room: 316)
  )
}
