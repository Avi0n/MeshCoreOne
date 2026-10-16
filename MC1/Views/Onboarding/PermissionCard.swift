import SwiftUI

struct PermissionCard: View {
  @Environment(\.openURL) private var openURL

  let icon: String
  let title: String
  let description: String
  let isGranted: Bool
  let isDenied: Bool
  var isOptional: Bool = false
  let action: () -> Void

  var body: some View {
    HStack(spacing: 16) {
      // Icon
      Image(systemName: icon)
        .font(.title2)
        .foregroundStyle(iconColor)
        .frame(width: 44, height: 44)
        .background(iconColor.opacity(0.1), in: .circle)

      // Text
      VStack(alignment: .leading, spacing: 4) {
        HStack {
          Text(title)
            .font(.headline)

          if isOptional {
            Text(L10n.Onboarding.Permissions.optional)
              .font(.caption)
              .padding(.horizontal, 6)
              .padding(.vertical, 2)
              .background(.secondary.opacity(0.2), in: .capsule)
              .foregroundStyle(.secondary)
          }
        }

        Text(description)
          .font(.subheadline)
          .foregroundStyle(.secondary)
      }

      Spacer()

      // Status/Action
      if isGranted {
        Image(systemName: "checkmark.circle.fill")
          .font(.title2)
          .foregroundStyle(.green)
      } else if isDenied {
        Button(L10n.Onboarding.Permissions.openSettings) {
          if let url = URL(string: UIApplication.openSettingsURLString) {
            openURL(url)
          }
        }
        .buttonStyle(.bordered)
        .controlSize(.small)
      } else {
        Button(L10n.Onboarding.Permissions.request) {
          action()
        }
        .buttonStyle(.borderedProminent)
        .controlSize(.small)
      }
    }
    .padding()
    .liquidGlass(in: .rect(cornerRadius: 12))
  }

  private var iconColor: Color {
    if isGranted {
      .green
    } else if isDenied {
      .orange
    } else {
      .accentColor
    }
  }
}
