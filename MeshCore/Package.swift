// swift-tools-version: 6.4
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
    // swift-crypto backs CryptoKit on non-Apple platforms; CryptoExtras AES-CBC
    // emulates AES-128-ECB (ChannelCrypto / DirectMessageCrypto). Requires 4.0+
    // for the CryptoExtras product name (was `_CryptoExtras` in 3.x).
    .package(url: "https://github.com/apple/swift-crypto.git", "5.0.0"..<"6.0.0")
  ],
  targets: [
    .target(
      name: "MeshCore",
      dependencies: [
        .product(name: "Crypto", package: "swift-crypto"),
        .product(name: "CryptoExtras", package: "swift-crypto")
      ]
    ),
    .target(
      name: "MeshCoreTestSupport",
      dependencies: ["MeshCore"],
      path: "Tests/MeshCoreTestSupport"
    ),
    .testTarget(
      name: "MeshCoreTests",
      dependencies: ["MeshCore", "MeshCoreTestSupport"]
    )
  ]
)
