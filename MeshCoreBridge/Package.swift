// swift-tools-version: 6.4
import CompilerPluginSupport
import PackageDescription

// Android-only static-link flags for the single bridge .so. Both must stay OFF the
// command line: passing -static-stdlib or an Android -resource-dir via -Xswiftc also
// hits the Apple host jextract tool build, which is a hard error. Gating them in the
// manifest with `.when(platforms: [.android])` keeps the host toolchain untouched.
// The resource dir is arch-specific (swift_static-aarch64 vs swift_static-x86_64) and
// machine-specific, so the Gradle cross-build passes it per ABI via this env var and
// touches Package.swift to defeat SwiftPM's manifest cache. Empty when building for a
// non-Android target (Apple/host), where the array below stays empty.
let androidStaticLinkFlags: [String] = {
  // PackageDescription snapshot — avoid Foundation/ProcessInfo in the host manifest compile.
  guard let resourceDir = Context.environment["MESHCORE_ANDROID_SWIFT_STATIC_RESOURCE_DIR"],
        !resourceDir.isEmpty else { return [] }
  // The Android static link is single-pass and -static-stdlib does not pull every archive a
  // dependency needs, so some cross-references stay unresolved: the .so still links (undefined
  // symbols are allowed in a shared object) but dlopen fails at load. Wrapping the relevant archives
  // in a linker group makes the linker re-scan them until no undefined symbol remains. Two gaps seen:
  //   * libFoundation.a references CoreFoundation SPIs (CFURLComponentsGetURLUserAllowedCharacterSet)
  //     defined only in libCoreFoundation.a;
  //   * Foundation references Synchronization._MutexHandle (_lockSlow/_unlockSlow) defined in
  //     libswiftSynchronization.a.
  let androidLibDir = (resourceDir.hasSuffix("/") ? resourceDir : resourceDir + "/") + "android/"
  let groupedArchives = [
    "libCoreFoundation.a",
    "libFoundation.a",
    "libFoundationEssentials.a",
    "libFoundationInternationalization.a",
    "lib_FoundationICU.a",
    "lib_FoundationCShims.a",
    "lib_FoundationCollections.a",
    "libswiftSynchronization.a"
  ].map { androidLibDir + $0 }
  var flags = ["-static-stdlib", "-resource-dir", resourceDir, "-Xlinker", "--start-group"]
  for archive in groupedArchives {
    flags += ["-Xlinker", archive]
  }
  flags += ["-Xlinker", "--end-group"]
  return flags
}()

// The bridge is a synchronous, callback-shaped facade over the MeshCore actor API
// so a JNI layer can drive it from Kotlin. The Android build links MeshCore, the
// swift-java runtime, and the Swift standard library statically into a single bridge
// .so (the product is `.dynamic` so SwiftPM emits one .so; every dependency below is
// a static/automatic product absorbed into it). Shipping two runtime copies (a dynamic
// MeshCore.so plus a static-stdlib bridge.so) crashes on the first actor hop, so the
// one-.so rule is mandatory and enforced at the Gradle step, not here.
//
// The Swift module is named `MeshCoreBridgeKit`, distinct from the public
// `MeshCoreBridge` class it exports. jextract writes one Java file per exported type
// AND a module-level class named after the Swift module; when the module and the
// primary type share a name, the module file clobbers the type file and drops every
// method. The `Kit` suffix keeps the two names apart so the full `MeshCoreBridge` Java
// class (all commands + the initializer) survives; the empty `MeshCoreBridgeKit` module
// class is harmless. Kotlin still sees `one.meshcore.bridge.MeshCoreBridge`.
//
// swift-java, the `SwiftJavaStatic` product, the JExtractSwiftPlugin, and
// `Sources/MeshCoreBridge/swift-java.config` are Android-only build wiring that
// generates the JNI bindings for this surface. No public API changes because of them,
// and no Apple target depends on this package.
let package = Package(
  name: "MeshCoreBridge",
  platforms: [
    .iOS(.v18),
    .macOS(.v15)
  ],
  products: [
    .library(
      name: "MeshCoreBridgeKit",
      type: .dynamic,
      targets: ["MeshCoreBridgeKit"]
    )
  ],
  dependencies: [
    .package(path: "../MeshCore"),
    // swift-java 0.4.2. Pinned by exact revision so jextract output stays byte-stable
    // across machines; the transitive swift-java-jni-core resolves to 0.5.1
    // (b440a0be98381550cf189c903782e817619ffa05). This is a pre-1.0, Maven-Central-less
    // dependency; see multiplatform/swift-core/README.md for the mavenLocal prerequisite.
    .package(
      url: "https://github.com/swiftlang/swift-java",
      revision: "3555583ad6ed9cc14461087b4b088dcdcf510cf0"
    )
  ],
  targets: [
    .target(
      name: "MeshCoreAndroidICUData",
      path: "Sources/MeshCoreAndroidICUData",
      publicHeadersPath: "include",
      cSettings: [
        .headerSearchPath(".")
      ]
    ),
    .target(
      name: "MeshCoreBridgeKit",
      dependencies: [
        .product(name: "MeshCore", package: "MeshCore"),
        // SwiftJavaStatic is the auto-linkage swift-java runtime product: absorbed
        // into a `.dynamic` consumer (the single Android bridge .so) instead of
        // shipping as a second libSwiftJava.so with its own runtime copy.
        .product(name: "SwiftJavaStatic", package: "swift-java"),
        // Slim icudt76_dat object. Linked only on Android so the SDK archive's
        // 32 MiB packaged data member is never extracted. Apple builds skip it.
        .target(name: "MeshCoreAndroidICUData", condition: .when(platforms: [.android]))
      ],
      path: "Sources/MeshCoreBridge",
      linkerSettings: [
        .unsafeFlags(androidStaticLinkFlags, .when(platforms: [.android]))
      ],
      plugins: [
        .plugin(name: "JExtractSwiftPlugin", package: "swift-java")
      ]
    ),
    .testTarget(
      name: "MeshCoreBridgeTests",
      dependencies: ["MeshCoreBridgeKit"]
    )
  ]
)
