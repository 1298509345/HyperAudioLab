import Foundation

@main
struct ControlIntentChecks {
    @MainActor static func main() async throws {
        let receiver = Receiver.shared
        receiver.reset()
        _ = try await ToggleReceiverIntent().perform()
        precondition(receiver.running && !receiver.paused && receiver.resumes == 1 && receiver.stops == 0)
        _ = try await ToggleReceiverIntent().perform()
        precondition(!receiver.running && receiver.resumes == 1 && receiver.stops == 1)
        _ = try await ToggleReceiverIntent().perform()
        precondition(receiver.running && receiver.resumes == 2 && receiver.stops == 1)

        receiver.reset(running: true, paused: true)
        _ = try await ToggleReceiverIntent().perform()
        precondition(receiver.running && !receiver.paused && receiver.resumes == 1 && receiver.stops == 0)

        receiver.reset(succeeds: false)
        var failed = false
        do { _ = try await ToggleReceiverIntent().perform() }
        catch { failed = true; precondition(error.localizedDescription == receiver.status) }
        precondition(failed && !receiver.running && receiver.resumes == 1 && receiver.stops == 0)

        receiver.reset(running: true)
        _ = try await StartReceiverIntent().perform()
        precondition(receiver.running && receiver.stops == 0)
        _ = try await StopReceiverIntent().perform()
        precondition(!receiver.running && receiver.stops == 1)

        receiver.reset(running: true)
        let set = SetReceiverEnabledIntent()
        set.value = true
        _ = try await set.perform()
        precondition(receiver.running && receiver.stops == 0 && receiver.resumes == 0)
        set.value = false
        _ = try await set.perform()
        precondition(!receiver.running && receiver.stops == 1)
        _ = try await set.perform()
        precondition(!receiver.running && receiver.resumes == 0 && receiver.stops == 2)

        receiver.reset(running: true, paused: true)
        set.value = true
        _ = try await set.perform()
        precondition(receiver.running && !receiver.paused && receiver.stops == 0)

        receiver.reset(succeeds: false)
        failed = false
        do { _ = try await set.perform() }
        catch { failed = true; precondition(error.localizedDescription == receiver.status) }
        precondition(failed && !receiver.running && receiver.stops == 0)
        print("RECEIVER_CONTROL_INTENT_CHECKS_PASSED")
    }
}
