import Foundation

// Only replaces the hardware/network boundary, while the real AppIntent is compiled.
final class Receiver {
    static let shared = Receiver()
    var running = false
    var paused = false
    var resumeSucceeds = true
    var resumes = 0
    var stops = 0
    var status = "receiver unavailable"
    var diagnostics = "diagnostics"
    func resume() {
        resumes += 1
        if resumeSucceeds { running = true; paused = false }
    }
    func stop() { stops += 1; running = false; paused = false }
    func runDiagnostics() {}
    func reset(running: Bool = false, paused: Bool = false, succeeds: Bool = true) {
        self.running = running; self.paused = paused; resumeSucceeds = succeeds
        resumes = 0; stops = 0
    }
}
