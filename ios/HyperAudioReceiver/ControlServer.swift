import Foundation
import Combine
import Network
import CryptoKit

struct PairRequest: Identifiable {
    let id: UUID
    let name: String
}

/// Small, bounded, newline-delimited control protocol. Audio remains unencrypted LAN PCM.
final class ControlServer: ObservableObject {
    var issueToken: (() -> String)?
    var statusProvider: (() -> [String: Any])?
    var isReceiving: (() -> Bool)?
    @Published var pending: PairRequest?
    @Published var status = "未启动"
    @Published private(set) var devices: [PairedDevice] = []
    @Published private(set) var activeClientID: String?
    private var listener: NWListener?
    private var peers: [UUID: Peer] = [:]
    private var token = ""
    private let defaults: UserDefaults
    private let displayStore: PairedDeviceStore
    private var trusted: [String: String]
    private let receiverID: String
    private final class Peer {
        let id = UUID()
        let connection: NWConnection
        var bytes = Data()
        var clientID = ""
        var name = "Android"
        var nonce = ""
        var stage = 0
        init(_ connection: NWConnection) { self.connection = connection }
    }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        displayStore = PairedDeviceStore(defaults: defaults)
        trusted = defaults.dictionary(forKey: "pairedClientsV1") as? [String: String] ?? [:]
        receiverID = defaults.string(forKey: "receiverID") ?? UUID().uuidString.lowercased()
        defaults.set(receiverID, forKey: "receiverID")
        refreshDevices()
    }
    func start(token: String) throws {
        stop()
        self.token = token
        let parameters = NWParameters.tcp
        parameters.allowLocalEndpointReuse = true
        let server = try NWListener(using: parameters, on: NWEndpoint.Port(rawValue: 50006)!)
        listener = server
        server.service = NWListener.Service(name: "HyperAudio-" + String(receiverID.prefix(8)), type: "_hyperaudio._tcp")
        server.newConnectionHandler = { [weak self] connection in self?.accept(connection) }
        server.stateUpdateHandler = { [weak self, weak server] state in
            guard let self, let server, self.listener === server else { return }
            switch state {
            case .ready: self.status = "可自动发现；在Android点一键连接"
            case .failed(let error): self.status = "自动连接不可用：\(error.localizedDescription)；可用手动连接"
            default: break
            }
        }
        status = "正在开启自动连接"
        server.start(queue: .main)
    }
    private func accept(_ connection: NWConnection) {
        guard listener != nil, peers.count < 4 else { connection.cancel(); return }
        let p = Peer(connection)
        peers[p.id] = p
        connection.start(queue: .main)
        read(p)
        DispatchQueue.main.asyncAfter(deadline: .now() + 45) { [weak self, weak p] in
            guard let self, let p, self.peers[p.id] === p else { return }
            self.close(p)
        }
    }
    private func read(_ p: Peer) {
        p.connection.receive(minimumIncompleteLength: 1, maximumLength: 4096) { [weak self, weak p] data, _, complete, error in
            guard let self, let p, self.peers[p.id] === p else { return }
            if let data {
                p.bytes.append(data)
                guard p.bytes.count <= 4096 else { self.close(p); return }
                while let newline = p.bytes.firstIndex(of: 10), p.stage < 2 {
                    let line = Data(p.bytes[..<newline])
                    p.bytes.removeSubrange(...newline)
                    guard let json = try? JSONSerialization.jsonObject(with: line) as? [String: Any] else { self.close(p); return }
                    self.process(json, p)
                    guard self.peers[p.id] === p else { return }
                }
                // A pending approval must not accept additional buffered protocol messages.
                if p.stage == 2 && !p.bytes.isEmpty { self.close(p); return }
            }
            if error != nil || complete { self.close(p) }
            else { self.read(p) }
        }
    }
    private func process(_ json: [String: Any], _ p: Peer) {
        if p.stage == 0 {
            guard json["type"] as? String == "hello", json["v"] as? Int == 1,
                  let id = json["clientId"] as? String, UUID(uuidString: id) != nil else { close(p); return }
            p.clientID = id.lowercased()
            let name = json["name"] as? String ?? "Android"
            p.name = String(name.filter { !$0.isNewline && !$0.isASCIIControl }.prefix(48))
            p.nonce = UUID().uuidString.lowercased() + UUID().uuidString.lowercased()
            p.stage = 1
            send(["type": "challenge", "v": 1, "receiverId": receiverID,
                  "name": "HyperAudio-" + String(receiverID.prefix(8)), "nonce": p.nonce], p)
            return
        }
        guard p.stage == 1 else { close(p); return }
        if json["type"] as? String == "inspect", let secret = trusted[p.clientID],
           let proof = json["proof"] as? String,
           Self.validProof(proof, secret: secret, client: p.clientID, receiver: receiverID, nonce: p.nonce) {
            p.stage = 2
            let values = statusProvider?() ?? [:]
            // Whitelist public status fields; never expose token, secret or trust records.
            var response: [String: Any] = ["type": "status", "v": 1, "receiverId": receiverID,
                "name": "HyperAudio-" + String(receiverID.prefix(8)),
                "running": false, "listening": false, "paused": false, "bluetooth": false,
                "received": 0, "lastPacketAgeMs": -1, "queuedMS": 0]
            for key in ["running", "listening", "paused", "bluetooth", "received", "lastPacketAgeMs",
                        "queuedMS", "dropped", "concealed", "signalPeak", "outputVolume", "channels", "frameDurationMS"] {
                if let value = values[key] { response[key] = value }
            }
            send(response, p, final: true)
        } else if json["type"] as? String == "resume", let secret = trusted[p.clientID],
           let proof = json["proof"] as? String,
           Self.validProof(proof, secret: secret, client: p.clientID, receiver: receiverID, nonce: p.nonce) {
            ready(p, newSecret: nil)
        } else if json["type"] as? String == "pair" {
            guard pending == nil else { reject(p, reason: "busy"); return }
            p.stage = 2
            pending = PairRequest(id: p.id, name: p.name.isEmpty ? "Android" : p.name)
            status = "请确认Android的首次配对请求"
        } else { reject(p, reason: "pair_required") }
    }
    func approve(id: UUID) {
        guard let p = peers[id] else { pending = nil; return }
        pending = nil
        let secret = SymmetricKey(size: .bits256).withUnsafeBytes { data in data.map { String(format: "%02x", $0) }.joined() }
        trusted[p.clientID] = secret
        defaults.set(trusted, forKey: "pairedClientsV1")
        ready(p, newSecret: secret)
    }
    func deny(id: UUID) {
        guard let p = peers[id] else { pending = nil; return }
        pending = nil
        reject(p, reason: "denied")
    }
    private func ready(_ p: Peer, newSecret: String?) {
        p.stage = 2
        // A new sender session must replace the previous UDP peer and stale packets.
        let sessionToken = issueToken?() ?? token
        activeClientID = p.clientID
        displayStore.remember(id: p.clientID, name: p.name)
        refreshDevices()
        var response: [String: Any] = ["type": "ready", "v": 1, "receiverId": receiverID,
                                      "name": "HyperAudio-" + String(receiverID.prefix(8)), "token": sessionToken, "port": 50005,
                                      "audioFormats": ["HAP1", "HAP2"]]
        if let newSecret { response["secret"] = newSecret }
        status = "已连接 \(p.name)；等待 Android 系统采集授权"
        send(response, p, final: true)
    }
    private func reject(_ p: Peer, reason: String) { p.stage = 2; send(["type": "error", "reason": reason], p, final: true) }
    private func send(_ json: [String: Any], _ p: Peer, final: Bool = false) {
        guard var data = try? JSONSerialization.data(withJSONObject: json) else { close(p); return }
        data.append(10)
        p.connection.send(content: data, completion: .contentProcessed { [weak self, weak p] error in
            guard let self, let p else { return }
            if error != nil { self.close(p) }
            else if final {
                DispatchQueue.main.asyncAfter(deadline: .now() + 1) { [weak self, weak p] in
                    guard let self, let p else { return }; self.close(p)
                }
            }
        })
    }
    private func close(_ p: Peer) {
        if pending?.id == p.id { pending = nil; status = "配对等待结束，请在Android重试" }
        p.connection.cancel()
        peers.removeValue(forKey: p.id)
    }
    func forgetAll() {
        guard isReceiving?() != true else { status = "请先停止接收，再解除配对"; return }
        for p in Array(peers.values) { close(p) }
        trusted.removeAll(); defaults.removeObject(forKey: "pairedClientsV1")
        displayStore.removeAll(); activeClientID=nil; refreshDevices()
        status = "已忘记全部发端；下次连接需重新允许"
    }
    func rename(id: String, alias: String) {
        guard trusted[id] != nil else { return }
        displayStore.rename(id: id, alias: alias); refreshDevices()
    }
    @discardableResult func forget(id: String) -> Bool {
        guard !(isReceiving?() == true && activeClientID == id) else {
            status = "请先停止当前接收，再解除这台设备的配对"; return false
        }
        guard trusted.removeValue(forKey: id) != nil else { return false }
        for p in Array(peers.values) where p.clientID == id { close(p) }
        defaults.set(trusted, forKey: "pairedClientsV1")
        displayStore.remove(id: id)
        if activeClientID == id { activeClientID=nil }
        refreshDevices(); status="已解除这台设备的本地配对；下次连接需重新允许"
        return true
    }
    private func refreshDevices() { devices = displayStore.devices(trusted: Set(trusted.keys)) }
    func stop() {
        pending = nil
        activeClientID = nil
        listener?.cancel(); listener = nil
        for p in peers.values { p.connection.cancel() }; peers.removeAll()
        status = "未启动"
    }
    private static func validProof(_ proof: String, secret: String, client: String, receiver: String, nonce: String) -> Bool {
        guard proof.count == 64, secret.count == 64 else { return false }
        func bytes(_ hex: String) -> [UInt8]? {
            let chars = Array(hex.utf8)
            guard chars.count % 2 == 0 else { return nil }
            var result: [UInt8] = []
            for i in stride(from: 0, to: chars.count, by: 2) {
                guard let n = UInt8(String(decoding: chars[i...i+1], as: UTF8.self), radix: 16) else { return nil }
                result.append(n)
            }
            return result
        }
        guard let key = bytes(secret), let signature = bytes(proof) else { return false }
        let message = Data("HAC1|\(client)|\(receiver)|\(nonce)".utf8)
        return HMAC<SHA256>.isValidAuthenticationCode(signature, authenticating: message, using: SymmetricKey(data: key))
    }
}

private extension Character {
    var isASCIIControl: Bool { unicodeScalars.contains { $0.value < 32 || $0.value == 127 } }
}
