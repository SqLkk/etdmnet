import Foundation

/// Description of a single STUN/TURN endpoint.
///
/// Mirrors `dev.etdmnet.turn.IceServerSpec` from the JVM module so the same
/// credential payload can be deserialised on either side of the wire.
public struct IceServer: Sendable, Equatable {
    public let urls: [String]
    public let username: String?
    public let credential: String?

    public init(urls: [String], username: String? = nil, credential: String? = nil) {
        self.urls = urls
        self.username = username
        self.credential = credential
    }

    public static func stun(_ url: String) -> IceServer {
        IceServer(urls: [url])
    }

    public static func turn(urls: [String], username: String, credential: String) -> IceServer {
        IceServer(urls: urls, username: username, credential: credential)
    }
}

/// Role advertised by the local peer.
///
/// Wire-compatible with `dev.etdmnet.core.Role`.
public enum EtdmRole: String, Sendable, Equatable {
    case host        = "HOST"
    case backupHost  = "BACKUP_HOST"
    case client      = "CLIENT"
}

/// Snapshot of who is currently elected host.
public struct HostSnapshot: Sendable, Equatable {
    public let peerId: String
    public let confidence: Double
    public let elector: String
}

/// A message received from another peer over the DataChannel.
public struct IncomingMessage: Sendable {
    public let from: String
    public let payload: Data
}
