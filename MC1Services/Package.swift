// swift-tools-version: 6.0
import PackageDescription

let package = Package(
  name: "MC1Services",
  platforms: [.iOS(.v18), .macOS(.v15)],
  products: [
    .library(name: "MC1Services", targets: ["MC1Services"])
  ],
  dependencies: [
    .package(path: "../MeshCore")
  ],
  targets: [
    .target(
      name: "MC1Services",
      dependencies: ["MeshCore"]
    ),
    .testTarget(
      name: "MC1ServicesTests",
      dependencies: [
        "MC1Services",
        .product(name: "MeshCoreTestSupport", package: "MeshCore")
      ]
    )
  ]
)
