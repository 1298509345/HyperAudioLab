import Foundation

enum PlaybackMode: String, CaseIterable {
    case lowLatency, stable
    var title: String { self == .lowLatency ? "低延迟" : "稳定" }
    private func packets(milliseconds: Int, frames: Int) -> Int {
        max(1, Int(ceil(Double(milliseconds) * 48 / Double(max(1, frames)))))
    }
    func startupPackets(frames: Int) -> Int { packets(milliseconds: self == .lowLatency ? 20 : 80, frames: frames) }
    func queuePackets(frames: Int) -> Int { packets(milliseconds: self == .lowLatency ? 20 : 60, frames: frames) }
    func maximumPackets(frames: Int) -> Int { packets(milliseconds: self == .lowLatency ? 120 : 300, frames: frames) }
}

struct GainEnvelope {
    private var from: Float = 0
    private var target: Float = 0
    private var began: TimeInterval = 0
    private var duration: TimeInterval = 0
    static func clamp(_ value: Float) -> Float { value.isFinite ? min(1,max(0,value)) : 0.5 }
    func value(at now: TimeInterval) -> Float {
        guard duration > 0 else { return target }
        let progress = Float(min(1,max(0,(now-began)/duration)))
        return from + (target-from)*progress
    }
    mutating func ramp(to target: Float, now: TimeInterval, duration: TimeInterval) {
        from=value(at: now); self.target=Self.clamp(target); began=now; self.duration=max(0,duration)
    }
    mutating func mute() { from=0; target=0; duration=0 }
}

struct ReceiverSleepTimer {
    private var deadline: TimeInterval?
    private var session: UUID?
    mutating func arm(minutes: Int, now: TimeInterval, session: UUID) {
        guard minutes > 0 else { cancel(); return }
        self.session=session; deadline=now+Double(minutes)*60
    }
    mutating func cancel() { deadline=nil; session=nil }
    func deadline(for session: UUID) -> TimeInterval? { self.session == session ? deadline : nil }
    func remaining(now: TimeInterval, session: UUID) -> Int? {
        guard self.session == session, let deadline else { return nil }
        return max(0,Int(ceil(deadline-now)))
    }
    func expired(now: TimeInterval, session: UUID) -> Bool { remaining(now: now,session: session) == 0 }
}

enum ReceiverMaintenancePlan {
    static let stallDelay: TimeInterval = 0.5
    static let peerDelay: TimeInterval = 3

    static func repeatingMilliseconds(audioInterval: Int, background: Bool) -> Int? {
        background && audioInterval >= 250 ? nil : audioInterval
    }

    static func next(now: TimeInterval, lastPacket: TimeInterval?, streamStalled: Bool,
                     hasPeer: Bool, sleepDeadline: TimeInterval?, retryRecovery: Bool) -> TimeInterval? {
        var next = retryRecovery ? now + 1 : TimeInterval.infinity
        if let lastPacket {
            if !streamStalled { next = min(next, lastPacket + stallDelay) }
            if hasPeer { next = min(next, lastPacket + peerDelay) }
        }
        if let sleepDeadline {
            let minutes = ceil((sleepDeadline - now) / 60)
            let nextMinuteOrEnd = sleepDeadline - max(0, minutes - 1) * 60
            next = min(next, nextMinuteOrEnd)
        }
        return next.isFinite ? next : nil
    }
}
