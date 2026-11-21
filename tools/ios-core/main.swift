// Runs the production, platform-independent iOS protocol code on macOS.
// Only the random test name supplied by the Python harness may be approved.
import Foundation
import Combine
import Darwin

setbuf(stdout, nil)
let defaults = UserDefaults.standard
let domain = ProcessInfo.processInfo.processName
defaults.removePersistentDomain(forName: domain)

func check(_ condition: @autoclosure () -> Bool, _ message: String) {
    guard condition() else { fatalError(message) }
}
var packet = Data("HAP1".utf8)
packet.append(contentsOf: [1, 2, 3, 4])
packet.append(Data(repeating: 0, count: 8))
packet.append(Data("1a2b3c4d".utf8))
packet.append(contentsOf: [0, 128, 255, 127])
packet.append(Data(repeating: 0, count: 956))
let decoded = AudioPacket.decode(packet, token: "1a2b3c4d")
check(decoded?.sequence == 0x01020304, "wrong sequence endianness")
check(decoded?.pcm.count == 960, "wrong sample payload length")
check(decoded?.pcm.prefix(4) == Data([0, 128, 255, 127]), "wrong PCM boundaries")
check(AudioPacket.decode(packet.dropLast(), token: "1a2b3c4d") == nil, "accepted truncation")
check(AudioPacket.decode(packet + Data([0]), token: "1a2b3c4d") == nil, "accepted oversized packet")
check(AudioPacket.decode(packet, token: "abcd1234") == nil, "accepted wrong token")
var corrupt = packet
corrupt[0] = 0
check(AudioPacket.decode(corrupt, token: "1a2b3c4d") == nil, "accepted wrong magic")
var stereo = packet
stereo.replaceSubrange(0..<4, with: Data("HAP2".utf8))
let stereoDecoded = AudioPacket.decode(stereo, token: "1a2b3c4d")
check(stereoDecoded?.channels == 2 && stereoDecoded?.frames == 240, "HAP2 format was not decoded")
check(stereoDecoded?.sample(frame: 0, channel: 0) == -1, "left channel ordering is wrong")
check(abs((stereoDecoded?.sample(frame: 0, channel: 1) ?? 0) - Float(32767)/32768) < 0.00001, "right channel ordering is wrong")
check(decoded?.channels == 1 && decoded?.frames == 480, "legacy mono format changed")
print("PACKET_CHECKS_PASSED")

let server = ControlServer()
server.statusProvider = { ["running": true, "paused": false, "bluetooth": true,
                           "received": 42, "lastPacketAgeMs": 10, "queuedMS": 60,
                           "token": "must-not-leak", "secret": "must-not-leak"] }
let testName = ProcessInfo.processInfo.environment["HYPERAUDIO_TEST_NAME"]!
var generation: UInt32 = 0
server.issueToken = {
    generation += 1
    return String(format: "%08x", generation)
}
let approval = server.$pending.sink { request in
    guard let request else { return }
    DispatchQueue.main.async {
        if request.name == testName { server.approve(id: request.id) }
        else { server.deny(id: request.id) }
    }
}
let status = server.$status.sink { print("STATUS: \($0)") }
let ownerChecks = server.$activeClientID.sink { clientID in
    guard let clientID else { return }
    DispatchQueue.main.async {
        server.isReceiving = { true }
        check(!server.forget(id:clientID), "active sender must not be removed while receiving")
        let count=server.devices.count
        server.forgetAll()
        check(server.devices.count == count,"active session blocks clear-all")
        server.rename(id:clientID,alias:"smoke-alias")
        check(server.devices.first { $0.id == clientID }?.title == "smoke-alias", "rename uses authenticated identity")
        server.isReceiving = { false }
        print("ACTIVE_SENDER_GUARD_CHECKS_PASSED")
    }
}
try server.start(token: "1234abcd")
// A bounded lifetime and explicit cleanup keep this separate from app pairing data.
signal(SIGTERM, SIG_IGN)
let termination = DispatchSource.makeSignalSource(signal: SIGTERM, queue: .main)
termination.setEventHandler {
    server.stop()
    defaults.removePersistentDomain(forName: domain)
    defaults.synchronize()
    exit(0)
}
termination.resume()
DispatchQueue.main.asyncAfter(deadline: .now() + 60) {
    server.stop()
    defaults.removePersistentDomain(forName: domain)
    exit(2)
}
dispatchMain()
