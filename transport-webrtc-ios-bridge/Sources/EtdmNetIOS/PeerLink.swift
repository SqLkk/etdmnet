#if canImport(WebRTC)
import Foundation
import WebRTC

/// One WebRTC connection to a single remote peer, with a single
/// reliable + ordered DataChannel labeled `etdm`.
final class PeerLink: NSObject {
    private let factory: RTCPeerConnectionFactory
    private let iceServers: [IceServer]
    private let localPeerId: String
    private let remotePeerId: String
    private let initiator: Bool
    private let signaling: SignalingClient
    private let onFrame: (Data) -> Void

    private var pc: RTCPeerConnection!
    private var channel: RTCDataChannel?

    init(
        factory: RTCPeerConnectionFactory,
        iceServers: [IceServer],
        localPeerId: String,
        remotePeerId: String,
        initiator: Bool,
        signaling: SignalingClient,
        onFrame: @escaping (Data) -> Void
    ) {
        self.factory = factory
        self.iceServers = iceServers
        self.localPeerId = localPeerId
        self.remotePeerId = remotePeerId
        self.initiator = initiator
        self.signaling = signaling
        self.onFrame = onFrame
        super.init()

        let config = RTCConfiguration()
        config.iceServers = iceServers.map { spec in
            if let user = spec.username, let cred = spec.credential {
                return RTCIceServer(urlStrings: spec.urls, username: user, credential: cred)
            } else {
                return RTCIceServer(urlStrings: spec.urls)
            }
        }
        config.sdpSemantics = .unifiedPlan
        config.continualGatheringPolicy = .gatherContinually

        let constraints = RTCMediaConstraints(
            mandatoryConstraints: nil,
            optionalConstraints: nil
        )
        self.pc = factory.peerConnection(
            with: config,
            constraints: constraints,
            delegate: self
        )

        if initiator {
            let cfg = RTCDataChannelConfiguration()
            cfg.isOrdered = true
            cfg.isNegotiated = false
            self.channel = pc.dataChannel(forLabel: "etdm", configuration: cfg)
            self.channel?.delegate = self
        }
    }

    func send(_ data: Data) {
        guard let ch = channel, ch.readyState == .open else { return }
        ch.sendData(RTCDataBuffer(data: data, isBinary: true))
    }

    func close() {
        channel?.close()
        pc.close()
    }

    // MARK: - SDP

    func createOfferAndSend() async {
        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        do {
            let offer = try await pc.offer(for: constraints)
            try await pc.setLocalDescription(offer)
            await signaling.sendSignal(
                to: remotePeerId,
                payload: .init(
                    sdpType: "offer",
                    sdp: offer.sdp,
                    candidate: nil, sdpMid: nil, sdpMLineIndex: nil
                )
            )
        } catch {
            // swallow; production code should surface this
        }
    }

    func handleSignal(_ payload: SignalingClient.SignalPayload) async {
        if let sdpType = payload.sdpType, let sdp = payload.sdp {
            let type: RTCSdpType = sdpType == "offer" ? .offer : .answer
            let desc = RTCSessionDescription(type: type, sdp: sdp)
            do {
                try await pc.setRemoteDescription(desc)
                if type == .offer {
                    let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
                    let answer = try await pc.answer(for: constraints)
                    try await pc.setLocalDescription(answer)
                    await signaling.sendSignal(
                        to: remotePeerId,
                        payload: .init(
                            sdpType: "answer",
                            sdp: answer.sdp,
                            candidate: nil, sdpMid: nil, sdpMLineIndex: nil
                        )
                    )
                }
            } catch {
                // swallow
            }
        } else if let cand = payload.candidate {
            let ic = RTCIceCandidate(
                sdp: cand,
                sdpMLineIndex: Int32(payload.sdpMLineIndex ?? 0),
                sdpMid: payload.sdpMid
            )
            try? await pc.add(ic)
        }
    }
}

extension PeerLink: RTCPeerConnectionDelegate {
    func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {
        Task {
            await signaling.sendSignal(
                to: remotePeerId,
                payload: .init(
                    sdpType: nil,
                    sdp: nil,
                    candidate: candidate.sdp,
                    sdpMid: candidate.sdpMid,
                    sdpMLineIndex: Int(candidate.sdpMLineIndex)
                )
            )
        }
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {
        self.channel = dataChannel
        dataChannel.delegate = self
    }

    // Required (unused) delegate stubs
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {}
    func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
}

extension PeerLink: RTCDataChannelDelegate {
    func dataChannelDidChangeState(_ dataChannel: RTCDataChannel) {}
    func dataChannel(_ dataChannel: RTCDataChannel, didReceiveMessageWith buffer: RTCDataBuffer) {
        onFrame(buffer.data)
    }
}
#endif
