import Foundation

/// Ktor signaling protocol client.
///
/// Wire-compatible with `dev.etdmnet.signaling.SignalingProtocol`. Uses
/// `URLSessionWebSocketTask` so it is dependency-free.
public actor SignalingClient {
    public enum Event: Sendable {
        case peers([String])
        case peerJoined(String)
        case peerLeft(String)
        case signal(from: String, payload: SignalPayload)
        case error(String)
    }

    public struct SignalPayload: Codable, Sendable {
        public let sdpType: String?
        public let sdp: String?
        public let candidate: String?
        public let sdpMid: String?
        public let sdpMLineIndex: Int?
    }

    private let url: URL
    private let room: String
    private let peerId: String
    private var task: URLSessionWebSocketTask?
    private var continuation: AsyncStream<Event>.Continuation?

    public init(url: URL, room: String, peerId: String) {
        self.url = url
        self.room = room
        self.peerId = peerId
    }

    public func events() -> AsyncStream<Event> {
        AsyncStream { cont in
            self.continuation = cont
            Task { await self.start() }
        }
    }

    private func start() async {
        let session = URLSession(configuration: .default)
        let task = session.webSocketTask(with: url)
        self.task = task
        task.resume()

        await send(json: [
            "t": "Hello",
            "room": room,
            "peerId": peerId,
        ])

        await receiveLoop()
    }

    private func receiveLoop() async {
        guard let task else { return }
        do {
            let msg = try await task.receive()
            await dispatch(message: msg)
            await receiveLoop()
        } catch {
            continuation?.yield(.error("ws receive: \(error.localizedDescription)"))
            continuation?.finish()
        }
    }

    private func dispatch(message: URLSessionWebSocketTask.Message) async {
        switch message {
        case .string(let s):
            guard let data = s.data(using: .utf8) else { return }
            await dispatch(data: data)
        case .data(let d):
            await dispatch(data: d)
        @unknown default:
            break
        }
    }

    private func dispatch(data: Data) async {
        guard let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return }
        let t = obj["t"] as? String ?? ""
        switch t {
        case "Peers":
            let ids = obj["peers"] as? [String] ?? []
            continuation?.yield(.peers(ids))
        case "PeerJoined":
            if let p = obj["peerId"] as? String { continuation?.yield(.peerJoined(p)) }
        case "PeerLeft":
            if let p = obj["peerId"] as? String { continuation?.yield(.peerLeft(p)) }
        case "Signal":
            if let from = obj["from"] as? String,
               let payloadObj = obj["payload"] as? [String: Any],
               let pdata = try? JSONSerialization.data(withJSONObject: payloadObj),
               let payload = try? JSONDecoder().decode(SignalPayload.self, from: pdata) {
                continuation?.yield(.signal(from: from, payload: payload))
            }
        default:
            break
        }
    }

    public func sendSignal(to target: String, payload: SignalPayload) async {
        let p: [String: Any?] = [
            "sdpType": payload.sdpType,
            "sdp": payload.sdp,
            "candidate": payload.candidate,
            "sdpMid": payload.sdpMid,
            "sdpMLineIndex": payload.sdpMLineIndex,
        ]
        await send(json: [
            "t": "Signal",
            "from": peerId,
            "to": target,
            "payload": p.compactMapValues { $0 },
        ])
    }

    public func close() async {
        task?.cancel(with: .goingAway, reason: nil)
        continuation?.finish()
    }

    private func send(json obj: [String: Any]) async {
        guard let data = try? JSONSerialization.data(withJSONObject: obj),
              let s = String(data: data, encoding: .utf8) else { return }
        do {
            try await task?.send(.string(s))
        } catch {
            continuation?.yield(.error("ws send: \(error.localizedDescription)"))
        }
    }
}
