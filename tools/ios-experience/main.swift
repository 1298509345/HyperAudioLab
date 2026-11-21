import Foundation

func check(_ value: @autoclosure () -> Bool, _ message: String) {
    guard value() else { fatalError(message) }
}
let suite = "hyperaudio-experience-" + UUID().uuidString
let defaults = UserDefaults(suiteName: suite)!
defer { defaults.removePersistentDomain(forName: suite) }
let store = PairedDeviceStore(defaults: defaults)
let a = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
let b = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
let trusted: Set<String> = [a,b]
defaults.set([a:"secret-a",b:"secret-b"], forKey: "pairedClientsV1")
defaults.set("keep", forKey: "unrelated")
check(store.devices(trusted: trusted).count == 2, "legacy trust records must be visible")
check(store.devices(trusted: trusted).allSatisfy { $0.name.hasPrefix("已配对设备") }, "legacy fallback names")
store.remember(id:a, name:"old-device", seen:Date(timeIntervalSince1970:10))
store.rename(id:a, alias:"  我的手机  ")
store.remember(id:a, name:"new-device", seen:Date(timeIntervalSince1970:20))
check(store.devices(trusted:trusted).first { $0.id == a }?.title == "我的手机", "alias survives reconnect")
check(store.devices(trusted:trusted).first { $0.id == a }?.name == "new-device", "authenticated name updated")
let reopened = PairedDeviceStore(defaults:defaults)
check(reopened.devices(trusted:trusted).first { $0.id == a }?.lastSeen == Date(timeIntervalSince1970:20), "metadata persisted")
reopened.remove(id:a)
check(reopened.devices(trusted:[b]).map(\.id) == [b], "precise metadata removal")
check((defaults.dictionary(forKey:"pairedClientsV1") as? [String:String])?[b] == "secret-b", "metadata cannot change trust")
check(defaults.string(forKey:"unrelated") == "keep", "unrelated defaults preserved")
reopened.rename(id:b, alias:String(repeating:"x",count:100)+"\n")
check(reopened.devices(trusted:[b]).first!.alias.count == 48, "bounded alias")
check(!reopened.devices(trusted:[b]).first!.alias.contains("\n"), "control chars removed")
check(reopened.devices(trusted:[]).isEmpty, "orphan metadata cannot authorize a device")
let server=ControlServer(defaults:defaults)
check(server.devices.count == 2,"server migrates both legacy trusted records")
server.rename(id:a,alias:"controller-alias")
check(server.devices.first { $0.id == a }?.title == "controller-alias", "server exposes metadata updates")
check(server.forget(id:a),"remove one inactive sender")
check(server.devices.map(\.id) == [b],"other sender remains authorized")
check((defaults.dictionary(forKey:"pairedClientsV1") as? [String:String])?[b] == "secret-b", "server preserves unrelated trust")
server.isReceiving={true}; server.forgetAll()
check(server.devices.count == 1,"clear all refused during reception")
server.isReceiving={false}; server.forgetAll()
check(server.devices.isEmpty,"clear all succeeds while stopped")
check(defaults.string(forKey:"unrelated") == "keep","server trust edits preserve unrelated preferences")

var input = ReceiverStateInput()
check(input.phase == .idle, "initial state")
input.failed=true
check(input.phase == .failed, "failure surfaced")
input.failed=false; input.endedByTimer=true
check(input.phase == .sleepEnded, "timer stop surfaced")
input.endedByTimer=false; input.running=true
check(input.phase == .noBluetooth, "missing media route blocks rendering")
input.bluetooth=true
check(input.phase == .starting, "not listening yet")
input.networkWaiting=true
check(input.phase == .waitingNetwork, "network waiting cannot be presented as initial startup")
input.networkWaiting=false
input.listening=true
check(input.phase == .ready, "listening does not prove playback")
input.buffered=true
check(input.phase == .playing, "actual scheduled playback")
input.userPaused=true
check(input.phase == .userPaused, "user pause wins over buffered")
input.userPaused=false; input.systemBlocked=true
check(input.phase == .systemPaused, "system interruption")
input.systemBlocked=false; input.localAudioPause=true
check(input.phase == .localPriority, "local audio priority")
input.localAudioPause=false; input.stalled=true
check(input.phase == .reconnecting, "stalled stream")
input.bluetooth=false
check(input.phase == .noBluetooth, "route loss takes precedence over network")
check(!ReceiverPhase.playing.title.contains("已送达"), "software state cannot certify hearing")
var stopped=ReceiverStateInput()
stopped.stopPhase = .noBluetooth
check(stopped.phase == .noBluetooth,"failed start surfaces missing route")
stopped.bluetooth=true
check(stopped.phase == .idle,"selecting a media headset clears stale missing-route prompt")

let report = ReceiverReport(version:"0.6",system:"iOS 27.2",mode:"stable",channels:2,
                            running:true,paused:false,seconds:12,received:24,dropped:1,concealed:2,
                            routeType:"bluetoothA2DP",phase:.playing).text
let json = try JSONSerialization.jsonObject(with:Data(report.utf8)) as! [String:Any]
let allowed:Set<String> = ["schema","version","system","mode","channels","running","paused","seconds","received","dropped","concealed","routeType","state"]
check(Set(json.keys) == allowed, "report schema must be exact allowlist")
check(json["channels"] as? Int == 2 && json["received"] as? Int == 24, "report preserves nonprivate facts")
for secret in ["secret-a","secret-b","我的手机","old-device","new-device",a,b,"pairedClientsV1","192.0.2.17","/private/path"] {
    check(!report.contains(secret), "report leaked private value: " + secret)
}
let tainted = ReceiverReport(version:"0.6",system:"iOS",mode:"192.0.2.17",channels:7,
                             running:false,paused:false,seconds:-1,received:-4,dropped:-2,concealed:-3,
                             routeType:"private-phone",phase:.idle).text
check(!tainted.contains("192.0.2.17") && !tainted.contains("private-phone"), "untrusted free text cannot enter enum fields")
check(tainted.contains("unknown"), "unknown route represented explicitly")
print("RECEIVER_EXPERIENCE_CHECKS_PASSED")
