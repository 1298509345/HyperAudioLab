"""Execute the production lock-screen command registration at the OS boundary."""
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


@unittest.skipUnless(sys.platform == 'darwin', 'requires Swift compiler')
class RemoteCommandTests(unittest.TestCase):
    def test_lock_screen_uses_pause_without_tearing_down_reception(self):
        source = (ROOT / 'ios/HyperAudioReceiver/Receiver.swift').read_text()
        start = source.index('    private func installRemoteCommands() {')
        end = source.index('    private func updateNowPlaying()', start)
        registration = source[start:end]
        # Replace only platform and playback boundaries; execute the actual registration.
        probe = '''import Foundation
enum MPRemoteCommandHandlerStatus { case success }
final class MPRemoteCommand {
    var isEnabled = true
    var handlers: [(Int) -> MPRemoteCommandHandlerStatus] = []
    func addTarget(handler: @escaping (Int) -> MPRemoteCommandHandlerStatus) -> Any {
        handlers.append(handler); return handlers.count
    }
    func send() { if isEnabled { for handler in handlers { _ = handler(0) } } }
}
final class MPRemoteCommandCenter {
    static let instance = MPRemoteCommandCenter()
    static func shared() -> MPRemoteCommandCenter { instance }
    let playCommand = MPRemoteCommand(), pauseCommand = MPRemoteCommand()
    let stopCommand = MPRemoteCommand(), togglePlayPauseCommand = MPRemoteCommand()
}
final class Receiver {
    var paused = false, running = true
    var stops = 0, pauses = 0, resumes = 0
    var remoteTargets: [(MPRemoteCommand, Any)] = []
    init() { installRemoteCommands() }
    func pause() { paused = true; pauses += 1 }
    func resume() { paused = false; resumes += 1 }
    func stop() { running = false; stops += 1 }
''' + registration + '''
}
@main struct Checks {
    @MainActor static func main() async throws {
        let receiver = Receiver(), center = MPRemoteCommandCenter.shared()
        precondition(!center.stopCommand.isEnabled, "Lock screen must not offer Stop")
        precondition(center.stopCommand.handlers.isEmpty, "Stop must not tear down reception")
        for command in [center.playCommand, center.pauseCommand, center.togglePlayPauseCommand] {
            precondition(command.isEnabled && command.handlers.count == 1)
        }
        center.pauseCommand.send()
        try await Task.sleep(nanoseconds: 20_000_000)
        precondition(receiver.paused && receiver.running && receiver.pauses == 1 && receiver.stops == 0)
        center.playCommand.send()
        try await Task.sleep(nanoseconds: 20_000_000)
        precondition(!receiver.paused && receiver.running && receiver.resumes == 1)
        center.togglePlayPauseCommand.send()
        try await Task.sleep(nanoseconds: 20_000_000)
        precondition(receiver.paused && receiver.running && receiver.stops == 0)
        center.togglePlayPauseCommand.send()
        try await Task.sleep(nanoseconds: 20_000_000)
        precondition(!receiver.paused && receiver.running && receiver.stops == 0)
        print("LOCK_SCREEN_PAUSE_RESUME_CHECKS_PASSED")
    }
}
'''
        with tempfile.TemporaryDirectory(prefix='hyperaudio-remote-commands-') as temp:
            path = Path(temp)
            (path / 'Probe.swift').write_text(probe)
            subprocess.run(['xcrun', 'swiftc', '-parse-as-library', str(path / 'Probe.swift'),
                            '-o', str(path / 'Probe')], check=True, timeout=90)
            result = subprocess.run([str(path / 'Probe')], text=True, capture_output=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn('LOCK_SCREEN_PAUSE_RESUME_CHECKS_PASSED', result.stdout)


if __name__ == '__main__':
    unittest.main(verbosity=2)
