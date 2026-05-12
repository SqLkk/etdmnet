import Foundation
#if canImport(WebRTC)
import WebRTC
#endif

/// Public entry point for an iOS peer joining an `etdmnet` room.
///
/// This wraps a `RTCPeerConnection` mesh, one DataChannel per remote peer
/// (label `etdm`, ordered + reliable), and routes everything through the
/// shared `SignalingClient` and `EtdmFrame` codec so the on-the-wire
/// representation matches the JVM/Android peers byte-for-byte.
///
/// > Important: This file requires the `WebRTC` SwiftPM dependency declared
/// > in `Package.swift`. When that package is unavailable (e.g. during
/// > Linux CI), the file degrades to a stub — see the `#if canImport(WebRTC)`
/// > guard.
public final class EtdmNet {
    public let peerId: String
    public let roomId: String

    private let signaling: SignalingClient
    private let iceServers: [IceServer]

    #if canImport(WebRTC)
    private let factory: RTCPeerConnectionFactory
    private var peers: [String: PeerLink] = [:]
    #endif

    private var onMessage: ((IncomingMessage) -> Void)?

    public init(
        signalingURL: URL,
        roomId: String,
        localPeerId: String,
        iceServers: [IceServer]
    ) {
        self.peerId = localPeerId
        self.roomId = roomId
        self.iceServers = iceServers
        self.signaling = SignalingClient(
            url: signalingURL,
            room: roomId,
            peerId: localPeerId
        )
        #if canImport(WebRTC)
        RTCInitializeSSL()
        let encoderFactory = RTCDefaultVideoEncoderFactory()
        let decoderFactory = RTCDefaultVideoDecoderFactory()
        self.factory = RTCPeerConnectionFactory(
            encoderFactory: encoderFactory,
            decoderFactory: decoderFactory
        )
        #endif
    }

    /// Establish the signaling connection and start handling incoming peers.
    /// New DataChannels are wired up automatically as peers join.
    public static func join(
        signalingURL: URL,
        roomId: String,
        localPeerId: String,
        iceServers: [IceServer]
    ) async throws -> EtdmNet {
        let net = EtdmNet(
            signalingURL: signalingURL,
            roomId: roomId,
            localPeerId: localPeerId,
            iceServers: iceServers
        )
        Task { await net.runSignalingLoop() }
        return net
    }

    /// Send an application payload to every connected peer.
    public func broadcast(_ payload: Data) {
        let frame = EtdmFrame.encode(tag: .app, from: peerId, payload: payload)
        #if canImport(WebRTC)
        for (_, link) in peers { link.send(frame) }
        #else
        _ = frame
        #endif
    }

    /// Register a closure invoked for every decoded incoming message.
    public func setMessageHandler(_ handler: @escaping (IncomingMessage) -> Void) {
        self.onMessage = handler
    }

    public func close() async {
        #if canImport(WebRTC)
        for (_, link) in peers { link.close() }
        peers.removeAll()
        #endif
        await signaling.close()
    }

    // MARK: - Internals

    private func runSignalingLoop() async {
        for await event in await signaling.events() {
            await handle(event: event)
        }
    }

    private func handle(event: SignalingClient.Event) async {
        #if canImport(WebRTC)
        switch event {
        case .peers(let ids):
            for id in ids where id != peerId { await dial(remote: id) }
        case .peerJoined(let id):
            // Higher peerId initiates to avoid glare.
            if peerId > id { await dial(remote: id) }
        case .peerLeft(let id):
            peers.removeValue(forKey: id)?.close()
        case .signal(let from, let payload):
            await handleSignal(from: from, payload: payload)
        case .error:
            break
        }
        #else
        _ = event
        #endif
    }

    #if canImport(WebRTC)
    private func dial(remote: String) async {
        let link = makeLink(remote: remote, initiator: true)
        peers[remote] = link
        await link.createOfferAndSend()
    }

    private func handleSignal(from: String, payload: SignalingClient.SignalPayload) async {
        let link = peers[from] ?? {
            let l = makeLink(remote: from, initiator: false)
            peers[from] = l
            return l
        }()
        await link.handleSignal(payload)
    }

    private func makeLink(remote: String, initiator: Bool) -> PeerLink {
        PeerLink(
            factory: factory,
            iceServers: iceServers,
            localPeerId: peerId,
            remotePeerId: remote,
            initiator: initiator,
            signaling: signaling,
            onFrame: { [weak self] frame in
                guard let self, let decoded = EtdmFrame.decode(frame) else { return }
                let msg = IncomingMessage(from: decoded.from, payload: decoded.payload)
                self.onMessage?(msg)
            }
        )
    }
    #endif
}
