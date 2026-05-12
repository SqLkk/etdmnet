import XCTest
@testable import EtdmNetIOS

final class EtdmFrameTests: XCTestCase {
    func test_roundtrip_app_payload() throws {
        let original = "hello mesh".data(using: .utf8)!
        let frame = EtdmFrame.encode(tag: .app, from: "phone-1", payload: original)
        let decoded = try XCTUnwrap(EtdmFrame.decode(frame))
        XCTAssertEqual(decoded.tag, .app)
        XCTAssertEqual(decoded.from, "phone-1")
        XCTAssertEqual(decoded.payload, original)
    }

    func test_roundtrip_control_payload_with_unicode_peer_id() throws {
        let bytes = Data([0xDE, 0xAD, 0xBE, 0xEF])
        let frame = EtdmFrame.encode(tag: .ctrl, from: "telefon-üst", payload: bytes)
        let decoded = try XCTUnwrap(EtdmFrame.decode(frame))
        XCTAssertEqual(decoded.tag, .ctrl)
        XCTAssertEqual(decoded.from, "telefon-üst")
        XCTAssertEqual(decoded.payload, bytes)
    }

    func test_truncated_frame_returns_nil() {
        let frame = EtdmFrame.encode(tag: .app, from: "x", payload: Data([1, 2, 3]))
        let truncated = frame.subdata(in: 0..<(frame.count - 1))
        XCTAssertNil(EtdmFrame.decode(truncated))
    }
}
