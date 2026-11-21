import Foundation
import Combine
import Network
import AVFoundation
import MediaPlayer
import Darwin
import UIKit
import WidgetKit

/// All mutable playback/network state is serialized on the main queue in this MVP.
final class Receiver: ObservableObject {
    static let shared = Receiver()
    let control = ControlServer()
    @Published var address = Receiver.localIPv4()
    @Published var token = Receiver.newToken()
    @Published var status = "未开始"
    @Published var route = "未检查"
    @Published var running = false { didSet { refreshControlState() } }
    @Published var listening = false
    @Published var received = 0
    @Published var concealed = 0
    @Published var dropped = 0
    @Published var queuedMS = 0
    @Published var paused = false { didSet { refreshControlState() } }
    @Published private(set) var bluetoothReady = false
    @Published private(set) var routeName = "未选择蓝牙耳机"
    @Published private(set) var routeType = "none"
    @Published private(set) var elapsedSeconds = 0
    @Published private(set) var lastStopPhase: ReceiverPhase = .idle
    @Published private(set) var networkWaiting = false
    @Published private(set) var channels = 1
    @Published private(set) var playbackMode = PlaybackMode(rawValue: UserDefaults.standard.string(forKey: "playbackMode") ?? "") ?? .stable
    @Published private(set) var gain = GainEnvelope.clamp((UserDefaults.standard.object(forKey: "receiverGain") as? NSNumber)?.floatValue ?? 0.5)
    @Published private(set) var prioritizeLocalAudio = UserDefaults.standard.object(forKey: "prioritizeLocalAudio") as? Bool ?? true
    @Published private(set) var sleepMinutes = 0
    @Published private(set) var sleepRemaining: Int?
    @Published var diagnostics = "点击一键自检，检查网络、收包与耳机路由。"
    private var policy = ReceiverPolicy()
    private var suppressAutomaticStart = false
    private var configuring = false
    private var streamStalled = false
    private var envelope = GainEnvelope()
    private var sleepTimer = ReceiverSleepTimer()
    private var sessionID = UUID()
    private var automaticRecoveryPending = false
    private var localAudioHint = false
    private var pumpIntervalMS = 250
    private var isBackground = UIApplication.shared.applicationState == .background
    private enum PumpSchedule: Equatable {
        case repeating(Int), once(TimeInterval), idle
    }
    private var pumpSchedule: PumpSchedule?
    private var lastPeak = 0
    private var lastNowPlaying: TimeInterval = 0
    private var nowPlayingSignature = ""
    private var remoteTargets: [(MPRemoteCommand, Any)] = []
    private var listener: NWListener?
    private var connections: [UUID: NWConnection] = [:]
    private var activePeer: UUID?
    private var activeRoute = ""
    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private var format = AVAudioFormat(standardFormatWithSampleRate: 48000, channels: 1)!
    private var frames: Int { 480 / channels }
    private var observers: [NSObjectProtocol] = []
    private var timer: DispatchSourceTimer?
    private var pending: [UInt32: Data] = [:]
    private var expected: UInt32?
    private var buffered = false
    private var queued = 0
    private var bufferPool: AudioBufferPool?
    private var cachedRouteSignature = ""
    private var beganAt: TimeInterval = 0
    private var epoch = UUID()
    private var lastPacket: TimeInterval = 0
    private var lastUI: TimeInterval = 0
    private var totalReceived = 0, totalConcealed = 0, totalDropped = 0
    private var controlStatePublisher: ReceiverControlStatePublisher?
    private var lastControlState = false

    init() {
        controlStatePublisher = ReceiverControlStatePublisher()
        engine.attach(player)
        engine.connect(player, to: engine.mainMixerNode, format: format)
        control.issueToken = { [weak self] in self?.prepareSender() ?? "" }
        control.statusProvider = { [weak self] in self?.inspectionStatus() ?? [:] }
        control.isReceiving = { [weak self] in self?.running ?? false }
        installRemoteCommands()
        observers.append(NotificationCenter.default.addObserver(forName: AVAudioSession.silenceSecondaryAudioHintNotification, object: nil, queue: .main) { [weak self] note in
            guard let self, self.running, self.prioritizeLocalAudio,
                  let raw = note.userInfo?[AVAudioSessionSilenceSecondaryAudioHintTypeKey] as? UInt,
                  let hint = AVAudioSession.SilenceSecondaryAudioHintType(rawValue: raw) else { return }
            self.localAudioHint = hint == .begin
            self.automaticRecoveryPending = true
            if self.localAudioHint {
                self.resetPlayback(); self.engine.pause(); self.paused=true
                self.status="iPhone 本机音频优先；转发输出暂时让路"
                self.setPumpInterval(250)
                self.refreshPumpSchedule()
                self.updateNowPlaying()
            } else { self.recoverAudio() }
        })
        observers.append(NotificationCenter.default.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] _ in
            guard let self else { return }
            self.updateRoute()
            if self.running && (!self.bluetoothOnly || self.routeSignature != self.activeRoute) {
                self.resetPlayback()
                self.engine.pause()
                self.paused = true
                self.status = "音频路由变化，等待蓝牙媒体输出恢复"
                self.recoverAudio()
            } else if self.running && self.needsAutomaticRecoveryCheck {
                // The same headset can return with its previous route identifier.
                self.recoverAudio()
            }
        })
        observers.append(NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
            guard let self, self.running,
                  let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                  let kind = AVAudioSession.InterruptionType(rawValue: raw) else { return }
            if kind == .began {
                self.policy.interruptionBegan()
                self.automaticRecoveryPending = true
                self.resetPlayback(); self.engine.pause(); self.paused = true
                self.status = "音频被系统中断；会话保留，等待系统允许恢复"
                self.setPumpInterval(250)
                self.updateNowPlaying()
            } else {
                let raw = note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0
                self.policy.interruptionEnded(shouldResume: AVAudioSession.InterruptionOptions(rawValue: raw).contains(.shouldResume))
                self.recoverAudio()
            }
        })
        observers.append(NotificationCenter.default.addObserver(forName: .AVAudioEngineConfigurationChange, object: engine, queue: .main) { [weak self] _ in
            guard let self, self.running, !self.configuring else { return }
            self.resetPlayback()
            self.recoverAudio()
        })
        observers.append(NotificationCenter.default.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
            self?.isBackground = true
            self?.refreshPumpSchedule()
        })
        observers.append(NotificationCenter.default.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main) { [weak self] _ in
            guard let self else { return }
            self.isBackground = false
            guard self.running, !self.enforceSleepDeadline() else { return }
            self.publishStatistics(now: ProcessInfo.processInfo.systemUptime)
            self.refreshPumpSchedule()
        })
        updateRoute()
    }
    private func refreshControlState() {
        let enabled = running && !paused
        guard enabled != lastControlState else { return }
        lastControlState = enabled
        controlStatePublisher?.publish(enabled)
        if #available(iOS 18.0, *) {
            ControlCenter.shared.reloadControls(ofKind: ReceiverControlChannel.kind)
        }
    }
    private func updateRoute() {
        let outputs=AVAudioSession.sharedInstance().currentRoute.outputs
        let description=outputs.map { "\($0.portName) [\($0.portType.rawValue)]" }.joined(separator: ", ")
        let ready = !outputs.isEmpty && outputs.allSatisfy { $0.portType == .bluetoothA2DP || $0.portType == .bluetoothLE }
        let name=outputs.map(\.portName).joined(separator: ", ")
        let type=outputs.isEmpty ? "none" : ready ? (outputs.allSatisfy { $0.portType == .bluetoothLE } ? "bluetoothLE" : "bluetoothA2DP") : "other"
        if route != description { route=description }
        if bluetoothReady != ready { bluetoothReady=ready }
        if routeName != name { routeName=name.isEmpty ? "未选择蓝牙耳机" : name }
        if routeType != type { routeType=type }
        cachedRouteSignature=outputs.map(\.uid).joined(separator:"|")
    }
    private var bluetoothOnly: Bool { bluetoothReady }
    private var routeSignature: String { cachedRouteSignature }
    var phase: ReceiverPhase {
        let localPause=paused && prioritizeLocalAudio && (localAudioHint || otherAudioActive)
        return ReceiverStateInput(running:running,listening:listening,bluetooth:bluetoothReady,buffered:buffered && !paused,
                                  userPaused:policy.userPaused,
                                  systemBlocked:policy.interrupted || policy.needsUserResume || (paused && !policy.userPaused && !localPause),
                                  localAudioPause:localPause,stalled:streamStalled,
                                  networkWaiting:networkWaiting,stopPhase:lastStopPhase).phase
    }
    var diagnosticReport: String {
        ReceiverReport(version:Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0.6",
                       system:"iOS " + UIDevice.current.systemVersion,mode:playbackMode.rawValue,channels:channels,
                       running:running,paused:paused,seconds:elapsedSeconds,received:totalReceived,dropped:totalDropped,
                       concealed:totalConcealed,routeType:routeType,phase:phase).text
    }
    func start(automatic: Bool = false) {
        guard !running else { return }
        if automatic && prioritizeLocalAudio && otherAudioActive {
            status="iPhone 正在播放音频；点开始接收可主动切换"; return
        }
        suppressAutomaticStart = false
        lastStopPhase = .idle; beganAt=ProcessInfo.processInfo.systemUptime; elapsedSeconds=0
        policy.start()
        sessionID=UUID(); automaticRecoveryPending=false; localAudioHint=false
        sleepTimer.arm(minutes: sleepMinutes, now: ProcessInfo.processInfo.systemUptime, session: sessionID)
        paused = false
        configuring = true
        defer { configuring = false }
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .default, options: [])
            try session.setPreferredSampleRate(48000)
            try session.setPreferredIOBufferDuration(0.01)
            try session.setActive(true)
            updateRoute()
            guard bluetoothOnly else { stop(message: "没有可用的蓝牙媒体输出；请连接并选择蓝牙耳机后重试", manual: false, phase:.noBluetooth); return }
            activeRoute=routeSignature
            address = Self.localIPv4()
            token = Self.newToken()
            totalReceived=0; totalConcealed=0; totalDropped=0
            received=0; concealed=0; dropped=0
            lastPeak=0; streamStalled=false
            player.volume=0
            engine.prepare(); try engine.start()
            running = true
            try beginListening()
            let t = DispatchSource.makeTimerSource(queue: .main)
            t.setEventHandler { [weak self] in
                guard let self else { return }
                if case .once = self.pumpSchedule { self.pumpSchedule = nil }
                self.pump()
                self.refreshPumpSchedule()
            }
            timer=t; pumpIntervalMS=250; pumpSchedule=nil
            refreshPumpSchedule(); t.resume()
            status="正在启动监听"
            updateNowPlaying()
        } catch { stop(message: "启动失败：\(error.localizedDescription)", manual: false, phase:.failed) }
    }
    private func beginListening() throws {
        networkWaiting=false
        let params=NWParameters.udp; params.allowLocalEndpointReuse=true
        let udp=try NWListener(using:params,on:NWEndpoint.Port(rawValue:50005)!)
        listener=udp
        udp.newConnectionHandler={ [weak self] c in self?.accept(c) }
        udp.stateUpdateHandler={ [weak self, weak udp] state in
            guard let self, let udp, self.listener === udp else { return }
            switch state {
            case .ready:
                self.listening=true; self.networkWaiting=false
                self.status="接收就绪；在 Android 点一键连接"
                do { try self.control.start(token:self.token) }
                catch { self.control.status="自动连接未能启动；可用手动信息：\(error.localizedDescription)" }
                self.updateNowPlaying()
            case .waiting(let error):
                self.listening=false; self.networkWaiting=true
                self.status="监听等待网络：\(error.localizedDescription)"
                self.updateNowPlaying()
            case .failed(let error): self.stop(message:"监听失败：\(error.localizedDescription)",manual:false,phase:.failed)
            default: break
            }
        }
        udp.start(queue:.main)
    }
    /// Retry only network resources; preserve policy, gain and the monotonic sleep deadline.
    func retryNetwork() {
        guard running else { start(); return }
        guard !listening else { return }
        control.stop(); listener?.cancel(); listener=nil; listening=false
        for c in connections.values { c.cancel() }
        connections.removeAll(); activePeer=nil; resetPlayback(); lastPacket=0
        setPumpInterval(250)
        refreshPumpSchedule()
        do { try beginListening(); status="正在重试接收网络" }
        catch { networkWaiting=true; status="网络重试失败；请检查网络后再试" }
    }
    private func accept(_ c: NWConnection) {
        guard running, activePeer == nil, connections.count < 4 else { c.cancel(); return }
        let id = UUID(); connections[id]=c
        c.start(queue: .main)
        receive(c,id)
        DispatchQueue.main.asyncAfter(deadline: .now()+5) { [weak self, weak c] in
            guard let self, self.activePeer != id else { return }
            c?.cancel(); self.connections.removeValue(forKey:id)
        }
    }
    private func receive(_ c: NWConnection, _ id: UUID) {
        c.receiveMessage { [weak self, weak c] data, _, _, error in
            guard let self, let c, self.running, self.connections[id] === c else { return }
            if let data, let p = AudioPacket.decode(data, token: self.token), self.activePeer == nil || self.activePeer == id {
                if self.activePeer == nil {
                    self.activePeer=id
                    let others = self.connections.filter { $0.key != id }
                    for (key,value) in others { value.cancel(); self.connections.removeValue(forKey:key) }
                }
                self.ingest(p)
            }
            if error == nil { self.receive(c,id) }
            else {
                c.cancel(); self.connections.removeValue(forKey:id)
                if self.activePeer == id {
                    self.activePeer = nil; self.resetPlayback()
                    self.status = "发端连接中断，保留接收并等待重连"
                    self.setPumpInterval(250)
                    self.refreshPumpSchedule()
                    self.updateNowPlaying()
                }
            }
        }
    }
    private func ingest(_ p: AudioPacket) {
        if enforceSleepDeadline() { return }
        let now=ProcessInfo.processInfo.systemUptime
        if channels != p.channels {
            do { try configureChannels(p.channels) }
            catch { stop(message:"声道切换失败：\(error.localizedDescription)",manual:false,phase:.failed); return }
        }
        let wasWaiting = lastPacket == 0 || streamStalled
        totalReceived += 1; lastPacket=now; streamStalled=false
        lastPeak = stride(from: 0, to: p.pcm.count, by: 2).reduce(0) { peak, offset in
            max(peak, abs(Int(Int16(bitPattern: UInt16(p.pcm[offset]) | UInt16(p.pcm[offset+1]) << 8))))
        }
        guard policy.canRender(bluetooth: bluetoothOnly, engineRunning: engine.isRunning) else {
            if wasWaiting { refreshPumpSchedule() }
            return
        }
        setPumpInterval(channels == 2 ? 5 : 10)
        if expected == nil { expected=p.sequence }
        let delta=Int32(bitPattern:p.sequence &- expected!)
        if delta < 0 { totalDropped += 1; return }
        if Int(delta) > playbackMode.maximumPackets(frames: frames) {
            // Bound latency after a burst, suspension, or sender stall.
            resetPlayback(); expected=p.sequence
        }
        if pending[p.sequence] != nil { totalDropped += 1; return }
        pending[p.sequence]=p.pcm
        if pending.count > playbackMode.maximumPackets(frames: frames) { resetPlayback(); expected=p.sequence; pending[p.sequence]=p.pcm }
    }
    private func resetPlayback() {
        epoch=UUID(); envelope.mute(); player.volume=0; player.stop()
        bufferPool=nil
        pending.removeAll(); expected=nil; queued=0; buffered=false
    }
    private func prepareSender() -> String {
        token = Self.newToken()
        for c in connections.values { c.cancel() }
        connections.removeAll(); activePeer = nil
        resetPlayback(); lastPacket = 0
        setPumpInterval(250)
        refreshPumpSchedule()
        updateNowPlaying()
        return token
    }
    private var needsAutomaticRecoveryCheck: Bool {
        automaticRecoveryPending && policy.canAutomaticallyResume && bluetoothOnly
    }
    private func publishStatistics(now: TimeInterval) {
        if received != totalReceived { received=totalReceived }
        if concealed != totalConcealed { concealed=totalConcealed }
        if dropped != totalDropped { dropped=totalDropped }
        let queueMS=queued*frames/48
        if queuedMS != queueMS { queuedMS=queueMS }
        let elapsed=Int(max(0,now-beganAt))
        if elapsedSeconds != elapsed { elapsedSeconds=elapsed }
        let remaining=sleepTimer.remaining(now: now,session: sessionID)
        if sleepRemaining != remaining { sleepRemaining=remaining }
        lastUI=now
    }
    private func pump() {
        guard running else { return }
        if enforceSleepDeadline() { return }
        let now=ProcessInfo.processInfo.systemUptime
        if needsAutomaticRecoveryCheck && (!prioritizeLocalAudio || !otherAudioActive) {
            recoverAudio()
        }
        if !isBackground && now-lastUI >= 1 { publishStatistics(now: now) }
        if lastPacket > 0 && now >= lastPacket + ReceiverMaintenancePlan.stallDelay && !streamStalled {
            resetPlayback(); streamStalled=true; status="网络无音频，等待重新缓冲"
            setPumpInterval(250)
        }
        if lastPacket > 0 && now >= lastPacket + ReceiverMaintenancePlan.peerDelay && activePeer != nil {
            for c in connections.values { c.cancel() }
            connections.removeAll(); activePeer=nil
        }
        if now-lastNowPlaying > 1 || (isBackground && pumpIntervalMS >= 250) {
            updateNowPlaying(); lastNowPlaying=now
        }
        guard policy.canRender(bluetooth: bluetoothOnly, engineRunning: engine.isRunning) else {
            if !paused { resetPlayback(); engine.pause(); paused=true; status="暂停输出；等待恢复或连接蓝牙耳机" }
            setPumpInterval(250)
            return
        }
        if !buffered && pending.count >= playbackMode.startupPackets(frames: frames) {
            buffered=true; envelope.ramp(to: gain, now: now, duration: 0.35)
            player.volume=0; player.play(); status="播放中 · \(channels == 2 ? "立体声" : "单声道") · \(playbackMode.title)"
        }
        player.volume=envelope.value(at: now)
        if buffered {
            while queued < playbackMode.queuePackets(frames: frames), let seq=expected {
                if bufferPool == nil {
                    bufferPool=AudioBufferPool(format:format,frames:frames,capacity:playbackMode.queuePackets(frames:frames)+4)
                }
                guard let pool=bufferPool else { break }
                guard let lease=pool.acquire() else {
                    if pool.inUseCount < pool.capacity { stop(message:"无法分配音频缓冲",phase:.failed) }
                    break
                }
                let bytes: Data
                if let pcm=pending.removeValue(forKey:seq) { bytes=pcm }
                else if pending.keys.contains(where: { Int(Int32(bitPattern:$0 &- seq)) >= playbackMode.startupPackets(frames: frames) }) {
                    bytes=Data(repeating:0,count:960); totalConcealed += 1
                } else { pool.release(lease); break }
                expected=seq &+ 1
                let b=lease.buffer
                guard let planes=b.floatChannelData else { pool.release(lease); stop(message:"无法访问音频缓冲",phase:.failed); return }
                let packet=AudioPacket(sequence:seq,pcm:bytes,channels:channels)
                for c in 0..<channels { for n in 0..<frames { planes[c][n]=packet.sample(frame:n,channel:c) } }
                queued += 1
                let generation=epoch
                // dataConsumed bounds the engine feeder; dataPlayedBack includes Bluetooth
                // hardware latency and can starve a short software queue on AirPods.
                player.scheduleBuffer(b, completionCallbackType:.dataConsumed) { [weak self, weak pool] _ in
                    DispatchQueue.main.async {
                        pool?.release(lease)
                        guard let self, self.epoch == generation else { return }
                        self.queued=max(0,self.queued-1)
                    }
                }
            }
        }
    }
    func stop(message: String = "已停止", manual: Bool = true, phase: ReceiverPhase = .idle) {
        // Mute before tearing down the graph; route transitions remain OS-controlled.
        if running && isBackground { publishStatistics(now: ProcessInfo.processInfo.systemUptime) }
        player.volume=0; running=false
        lastStopPhase=phase
        listening=false
        networkWaiting=false
        policy.stop(); paused=false
        sleepTimer.cancel(); sleepRemaining=nil; automaticRecoveryPending=false; localAudioHint=false
        if manual { suppressAutomaticStart=true }
        control.stop()
        timer?.cancel(); timer=nil; pumpSchedule=nil
        listener?.cancel(); listener=nil
        for c in connections.values { c.cancel() }; connections.removeAll(); activePeer=nil
        resetPlayback(); engine.stop(); lastPacket=0; queuedMS=0
        try? AVAudioSession.sharedInstance().setActive(false,options:.notifyOthersOnDeactivation)
        status=message
        MPNowPlayingInfoCenter.default().nowPlayingInfo=nil
        nowPlayingSignature=""
    }

    func enterForeground() {
        let enabled = UserDefaults.standard.object(forKey: "autoPrepare") as? Bool ?? true
        if enabled && !suppressAutomaticStart && !running { start(automatic:true) }
    }
    func pause() {
        guard running else { return }
        policy.pause(); paused=true; resetPlayback(); engine.pause()
        setPumpInterval(250)
        status="已暂停声音；保留接收会话"; updateNowPlaying()
        refreshPumpSchedule()
    }
    func resume() {
        guard running else { start(); return }
        policy.resume(); localAudioHint=false; recoverAudio(automatic:false)
    }
    private func recoverAudio(automatic: Bool = true) {
        guard running else { return }
        defer {
            refreshPumpSchedule()
            updateNowPlaying()
        }
        if enforceSleepDeadline() { return }
        if automatic && (!policy.canAutomaticallyResume || (prioritizeLocalAudio && otherAudioActive)) {
            paused=true
            automaticRecoveryPending=policy.canAutomaticallyResume
            status="iPhone 音频优先或系统尚未允许恢复；可手动恢复"
            setPumpInterval(250); return
        }
        if !automatic { updateRoute() }
        let canActivate = automatic
            ? policy.canRender(bluetooth: bluetoothOnly)
            : policy.canAttemptManualActivation(bluetooth: bluetoothOnly)
        guard canActivate else {
            automaticRecoveryPending=policy.canAutomaticallyResume
            paused=true; status="等待系统/蓝牙路由恢复；可在控制中心点恢复"; return
        }
        configuring=true
        defer { configuring=false }
        do {
            try AVAudioSession.sharedInstance().setActive(true)
            // Interruption end notifications can be absent even when reactivation succeeds.
            if !automatic { policy.manualActivationSucceeded() }
            updateRoute()
            guard bluetoothOnly else { paused=true; status="请先连接并选择蓝牙耳机"; return }
            if !engine.isRunning { engine.prepare(); try engine.start() }
            activeRoute=routeSignature; paused=false; resetPlayback()
            automaticRecoveryPending=false
            status="接收已恢复，等待新音频缓冲"
        } catch { paused=true; automaticRecoveryPending=false; status="恢复失败：\(error.localizedDescription)；请点击恢复重试" }
    }
    private var otherAudioActive: Bool {
        let session=AVAudioSession.sharedInstance()
        return session.isOtherAudioPlaying || session.secondaryAudioShouldBeSilencedHint
    }
    private func setPumpInterval(_ milliseconds: Int) {
        guard pumpIntervalMS != milliseconds else { return }
        pumpIntervalMS=milliseconds
        refreshPumpSchedule()
    }
    private func refreshPumpSchedule() {
        guard let timer else { return }
        let next: PumpSchedule
        if let interval = ReceiverMaintenancePlan.repeatingMilliseconds(audioInterval: pumpIntervalMS, background: isBackground) {
            next = .repeating(interval)
        } else if let deadline = ReceiverMaintenancePlan.next(now: ProcessInfo.processInfo.systemUptime,
                       lastPacket: lastPacket > 0 ? lastPacket : nil, streamStalled: streamStalled,
                       hasPeer: activePeer != nil, sleepDeadline: sleepTimer.deadline(for: sessionID),
                       retryRecovery: needsAutomaticRecoveryCheck) {
            next = .once(deadline)
        } else {
            next = .idle
        }
        guard next != pumpSchedule else { return }
        pumpSchedule = next
        switch next {
        case .repeating(let interval):
            timer.schedule(deadline: .now(), repeating: .milliseconds(interval), leeway: .milliseconds(interval >= 100 ? 20 : 1))
        case .once(let deadline):
            let delay = max(0, deadline - ProcessInfo.processInfo.systemUptime)
            timer.schedule(deadline: .now() + max(0.001, delay), leeway: .milliseconds(20))
        case .idle:
            timer.schedule(deadline: .distantFuture)
        }
    }
    private func configureChannels(_ count: Int) throws {
        guard count == 1 || count == 2 else { return }
        configuring=true
        defer { configuring=false }
        resetPlayback(); engine.stop(); engine.disconnectNodeOutput(player)
        channels=count
        format=AVAudioFormat(standardFormatWithSampleRate:48000,channels:AVAudioChannelCount(count))!
        engine.connect(player,to:engine.mainMixerNode,format:format)
        if !paused && policy.canRender(bluetooth:bluetoothOnly) && (!prioritizeLocalAudio || !otherAudioActive) {
            engine.prepare(); try engine.start()
        }
    }
    func setPlaybackMode(_ mode: PlaybackMode) {
        playbackMode=mode; UserDefaults.standard.set(mode.rawValue,forKey:"playbackMode")
        if running { resetPlayback(); status="已切换\(mode.title)，等待重新缓冲" }
    }
    func setGain(_ value: Float) {
        gain=GainEnvelope.clamp(value); UserDefaults.standard.set(gain,forKey:"receiverGain")
        if buffered { envelope.ramp(to:gain,now:ProcessInfo.processInfo.systemUptime,duration:0.15) }
    }
    func setLocalPriority(_ value: Bool) {
        prioritizeLocalAudio=value; UserDefaults.standard.set(value,forKey:"prioritizeLocalAudio")
        refreshPumpSchedule()
    }
    func setSleepMinutes(_ minutes: Int) {
        sleepMinutes=[0,15,30,60,120].contains(minutes) ? minutes : 0
        if running {
            sleepTimer.arm(minutes:sleepMinutes,now:ProcessInfo.processInfo.systemUptime,session:sessionID)
            sleepRemaining=sleepTimer.remaining(now:ProcessInfo.processInfo.systemUptime,session:sessionID)
            refreshPumpSchedule()
            updateNowPlaying()
        } else { sleepRemaining=nil }
    }
    private func enforceSleepDeadline() -> Bool {
        guard running, sleepTimer.expired(now:ProcessInfo.processInfo.systemUptime,session:sessionID) else { return false }
        stop(message:"睡眠定时已到，接收已停止",phase:.sleepEnded)
        return true
    }
    func runDiagnostics() {
        updateRoute(); address=Self.localIPv4()
        let values=inspectionStatus()
        diagnostics=ReceiverDiagnostics.report(
            addressAvailable: address.first?.isNumber == true, listening: listening,
            bluetooth: bluetoothOnly, volume: AVAudioSession.sharedInstance().outputVolume,
            packetAgeMS: values["lastPacketAgeMs"] as? Int ?? -1, signalPeak: lastPeak, paused: paused, receiverGain: gain)
        diagnostics += "\n\(channels == 2 ? "立体声" : "单声道") · \(playbackMode.title) · 接收增益 \(Int(gain*100))%\n累计收到 \(totalReceived) 包；补静音 \(totalConcealed)；丢弃 \(totalDropped)。"
    }
    private func inspectionStatus() -> [String: Any] {
        ["running": running, "listening": listening, "paused": paused, "bluetooth": bluetoothOnly,
         "received": totalReceived, "lastPacketAgeMs": lastPacket > 0 ? Int((ProcessInfo.processInfo.systemUptime-lastPacket)*1000) : -1,
         "queuedMS": queued*frames/48, "channels": channels, "frameDurationMS": frames/48,
         "dropped": totalDropped, "concealed": totalConcealed,
         "signalPeak": lastPeak, "outputVolume": AVAudioSession.sharedInstance().outputVolume]
    }
    private func installRemoteCommands() {
        let center=MPRemoteCommandCenter.shared()
        // Prefer the system Pause/Play control; Stop would end the receiver session.
        center.stopCommand.isEnabled=false
        let actions: [(MPRemoteCommand, (Receiver) -> Void)] = [
            (center.playCommand, { $0.resume() }), (center.pauseCommand, { $0.pause() }),
            (center.togglePlayPauseCommand, { $0.paused ? $0.resume() : $0.pause() })]
        for (command, action) in actions {
            let target=command.addTarget { [weak self] _ in
                DispatchQueue.main.async { if let self { action(self) } }
                return .success
            }
            remoteTargets.append((command,target)); command.isEnabled=true
        }
    }
    private func updateNowPlaying() {
        guard running else { return }
        let remaining = sleepTimer.remaining(now: ProcessInfo.processInfo.systemUptime, session: sessionID)
        let artist=status + (remaining.map { " · 睡眠剩余 \(($0+59)/60) 分钟" } ?? "")
        let signature="\(artist)|\(paused)|\(buffered)"
        guard signature != nowPlayingSignature else { return }
        nowPlayingSignature=signature
        MPNowPlayingInfoCenter.default().nowPlayingInfo = [
            MPMediaItemPropertyTitle: "HyperAudio 安卓音频",
            MPMediaItemPropertyArtist: artist,
            MPNowPlayingInfoPropertyIsLiveStream: true,
            MPNowPlayingInfoPropertyPlaybackRate: paused || !buffered ? 0.0 : 1.0]
    }
    private static func newToken() -> String { String(format:"%08x",UInt32.random(in:0...UInt32.max)) }
    static func localIPv4() -> String {
        var addresses = Set<String>()
        var list: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&list) == 0, let first=list else { return "无法读取，请查看 Wi-Fi 设置" }
        defer { freeifaddrs(list) }
        var current: UnsafeMutablePointer<ifaddrs>?=first
        while let p=current {
            let i=p.pointee
            let name = String(cString:i.ifa_name)
            if (name.hasPrefix("en") || name.hasPrefix("bridge") || name.hasPrefix("ap")),
               let a=i.ifa_addr, a.pointee.sa_family == UInt8(AF_INET) {
                var host=[CChar](repeating:0,count:Int(NI_MAXHOST))
                let capacity=socklen_t(host.count)
                if getnameinfo(a,socklen_t(a.pointee.sa_len),&host,capacity,nil,0,NI_NUMERICHOST) == 0 {
                    let value = String(cString:host)
                    if value != "127.0.0.1" && value != "0.0.0.0" { addresses.insert(value) }
                }
            }
            current=i.ifa_next
        }
        return addresses.isEmpty ? "未发现局域网地址；请查看 Wi-Fi / 热点设置" : addresses.sorted().joined(separator: " / ")
    }
    deinit {
        observers.forEach { NotificationCenter.default.removeObserver($0) }
        remoteTargets.forEach { $0.0.removeTarget($0.1) }
    }
}
