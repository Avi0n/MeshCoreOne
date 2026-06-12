import Foundation
import os

// MARK: - Parser Logger

let parserLogger = Logger(subsystem: "MeshCore", category: "Parsers")

/// Namespace for complex protocol parsers.
///
/// This enum contains specialized parsers for various mesh protocol data structures.
/// Each sub-parser is responsible for validating the input data size and correctly
/// interpreting multi-byte fields (mostly little-endian).
/// The leaf parsers here are invoked by ``PacketParser``, the response-code router.
public enum Parsers {}
