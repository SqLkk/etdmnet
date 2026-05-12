import Foundation

/// Wire-compatible encoder/decoder for `dev.etdmnet.codec.PeerMessageCodec`.
///
/// Frame layout (all little-endian):
///
///   ┌────────┬────────┬────────────────┬───────────────────────────────┐
///   │ len:u32│ tag:u8 │ from:varlen UTF8│ payload:varlen bytes          │
///   └────────┴────────┴────────────────┴───────────────────────────────┘
///
/// - `len` covers everything after itself (header + body).
/// - `tag = 0x01` → application payload (chat / state / action)
/// - `tag = 0x02` → ETDM control (host election sample)
///
/// Strings are encoded as `u16 length` + UTF-8 bytes. Payloads are encoded as
/// `u32 length` + raw bytes. This is the same byte layout produced by the
/// Kotlin `EncodedTransport`, so a JVM peer and this iOS peer agree on every
/// byte sent over the DataChannel.
public enum EtdmFrame {
    public enum Tag: UInt8 {
        case app  = 0x01
        case ctrl = 0x02
    }

    public struct Decoded: Sendable {
        public let tag: Tag
        public let from: String
        public let payload: Data
    }

    public static func encode(tag: Tag, from: String, payload: Data) -> Data {
        var body = Data()
        body.append(tag.rawValue)
        let fromBytes = Data(from.utf8)
        body.appendUInt16(UInt16(fromBytes.count))
        body.append(fromBytes)
        body.appendUInt32(UInt32(payload.count))
        body.append(payload)

        var out = Data()
        out.appendUInt32(UInt32(body.count))
        out.append(body)
        return out
    }

    public static func decode(_ data: Data) -> Decoded? {
        var cursor = 0
        guard let len: UInt32 = data.readUInt32(at: &cursor) else { return nil }
        guard data.count >= cursor + Int(len) else { return nil }

        guard let tagByte: UInt8 = data.readUInt8(at: &cursor),
              let tag = Tag(rawValue: tagByte) else { return nil }
        guard let fromLen: UInt16 = data.readUInt16(at: &cursor) else { return nil }
        guard let fromBytes = data.readBytes(at: &cursor, count: Int(fromLen)),
              let from = String(data: fromBytes, encoding: .utf8) else { return nil }
        guard let payloadLen: UInt32 = data.readUInt32(at: &cursor) else { return nil }
        guard let payload = data.readBytes(at: &cursor, count: Int(payloadLen)) else { return nil }

        return Decoded(tag: tag, from: from, payload: payload)
    }
}

// MARK: - Binary helpers (little-endian, matching Kotlin's `ByteBuffer.LITTLE_ENDIAN`)

private extension Data {
    mutating func appendUInt16(_ v: UInt16) {
        var le = v.littleEndian
        Swift.withUnsafeBytes(of: &le) { self.append(contentsOf: $0) }
    }
    mutating func appendUInt32(_ v: UInt32) {
        var le = v.littleEndian
        Swift.withUnsafeBytes(of: &le) { self.append(contentsOf: $0) }
    }

    func readUInt8(at cursor: inout Int) -> UInt8? {
        guard cursor + 1 <= count else { return nil }
        defer { cursor += 1 }
        return self[cursor]
    }
    func readUInt16(at cursor: inout Int) -> UInt16? {
        guard cursor + 2 <= count else { return nil }
        let v = UInt16(self[cursor]) | (UInt16(self[cursor + 1]) << 8)
        cursor += 2
        return v
    }
    func readUInt32(at cursor: inout Int) -> UInt32? {
        guard cursor + 4 <= count else { return nil }
        let v = UInt32(self[cursor])
            | (UInt32(self[cursor + 1]) << 8)
            | (UInt32(self[cursor + 2]) << 16)
            | (UInt32(self[cursor + 3]) << 24)
        cursor += 4
        return v
    }
    func readBytes(at cursor: inout Int, count: Int) -> Data? {
        guard cursor + count <= self.count else { return nil }
        let sub = self.subdata(in: cursor..<(cursor + count))
        cursor += count
        return sub
    }
}
