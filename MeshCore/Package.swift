// swift-tools-version: 6.2
import PackageDescription

let package = Package(
  name: "MeshCore",
  platforms: [
    .iOS(.v18),
    .macOS(.v15)
  ],
  products: [
    .library(
      name: "MeshCore",
      targets: ["MeshCore"]
    ),
    .library(
      name: "MeshCoreTestSupport",
      targets: ["MeshCoreTestSupport"]
    )
  ],
  dependencies: [
    .package(url: "https://github.com/apple/swift-docc-plugin", from: "1.4.0"),
    // swift-crypto backs CryptoKit off Apple. CryptoExtras emulates AES-128-ECB.
    // The portability fixture passed against 5.0.0. Apple builds use CryptoKit
    // and CommonCrypto, so those products are not linked there.
    .package(url: "https://github.com/apple/swift-crypto.git", exact: "5.0.0")
  ],
  targets: [
    .target(
      name: "MeshCore",
      dependencies: [
        .product(
          name: "Crypto",
          package: "swift-crypto",
          condition: .when(platforms: [.android, .linux, .windows])
        ),
        .product(
          name: "CryptoExtras",
          package: "swift-crypto",
          condition: .when(platforms: [.android, .linux, .windows])
        )
      ]
    ),
    .target(
      name: "MeshCoreTestSupport",
      path: "Tests/MeshCoreTestSupport"
    ),
    .testTarget(
      name: "MeshCoreTests",
      dependencies: ["MeshCore", "MeshCoreTestSupport"]
    )
  ]
)
