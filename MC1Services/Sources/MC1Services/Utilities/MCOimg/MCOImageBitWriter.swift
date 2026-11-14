import Foundation

struct MCOImageBitWriter {
  var bytes: [UInt8] = []
  var position = 0

  mutating func write(_ value: Int, _ bits: Int) {
    for shift in 0..<bits {
      if position % 8 == 0 { bytes.append(0) }
      bytes[position / 8] |= UInt8((value >> shift) & 1) << (position % 8)
      position += 1
    }
  }

  mutating func varUint(_ value: Int) {
    var remaining = value
    repeat {
      var byte = remaining & 127
      remaining >>= 7
      if remaining != 0 { byte |= 128 }
      write(byte, 8)
    } while remaining != 0
  }

  mutating func compact(_ value: Int) {
    if value <= 3 { write(0, 1); write(value, 2) } else if value <= 19 {
      write(1, 2); write(value - 4, 4)
    } else if value <= 275 {
      write(3, 3); write(value - 20, 8)
    } else {
      write(7, 3); varUint(value)
    }
  }

  mutating func bounded(_ value: Int, _ maximum: Int) {
    if maximum <= 7 { write(value, MCOImageBitReader.width(maximum)) } else if value <= 275 {
      compact(value)
    } else {
      write(7, 3); write(value - 276, MCOImageBitReader.width(maximum - 276))
    }
  }

  var data: Data { Data(bytes) }
}
