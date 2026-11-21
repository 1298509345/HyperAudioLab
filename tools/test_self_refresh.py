"""Execute production profile-refresh preflight on offline, synthetic profiles."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SDK = ROOT / "ios/Vendor/SelfRefreshKit/Sources/SelfRefreshKit"

SWIFT = r'''
import Foundation

struct ProbeError: Error {}
@main struct RefreshProbe {
    static func metadata(_ id: String, team: String = "TEAM", certs: [String] = ["AA"], expiry: Double = 4_000_000_000) -> ProvisioningProfileMetadata {
        ProvisioningProfileMetadata(uuid: id, name: id, appIDName: id, bundleIdentifier: id, teamIdentifier: team, teamName: team, creationDate: Date(timeIntervalSince1970: 0), expirationDate: Date(timeIntervalSince1970: expiry), certificateSHA1Fingerprints: certs)
    }
    static func data(_ id: String, team: String = "TEAM", certificate: Data = Data([1]), expiry: Double = 4_000_000_000, devices: [String] = []) throws -> Data {
        try PropertyListSerialization.data(fromPropertyList: ["UUID":id, "Name":id, "TeamIdentifier":[team], "CreationDate":Date(timeIntervalSince1970:0), "ExpirationDate":Date(timeIntervalSince1970:expiry), "DeveloperCertificates":[certificate], "Entitlements":["application-identifier":"\(team).\(id)"], "ProvisionedDevices":devices], format:.xml, options:0)
    }
    static func main() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let main = root.appendingPathComponent("Receiver.app")
        let ext = main.appendingPathComponent("PlugIns/Controls.appex")
        let phoneUDID = "00008140-0001000000000001"
        let anotherUDID = "00008140-0002000000000002"
        for (url,id) in [(main,"lab.receiver"),(ext,"lab.receiver.controls")] {
            try FileManager.default.createDirectory(at:url,withIntermediateDirectories:true)
            try PropertyListSerialization.data(fromPropertyList:["CFBundleIdentifier":id],format:.xml,options:0).write(to:url.appendingPathComponent("Info.plist"))
            try data(id,expiry:id.hasSuffix("controls") ? 3_000_000_000 : 4_000_000_000, devices:[phoneUDID]).write(to:url.appendingPathComponent("embedded.mobileprovision"))
        }
        let targets = try ProfileRefreshSafety.installedTargets(in:main)
        precondition(targets.map(\.bundleIdentifier) == ["lab.receiver","lab.receiver.controls"])
        let soleDevice = try await ProfileRefreshSafety.renewalDeviceUDID(targets: targets) {
            fatalError("the two installed profiles already identify the phone")
        }
        precondition(soleDevice == phoneUDID)
        let sharedProfile = try ProfileInspector.parseMobileProvisionData(data("lab.receiver", devices:[phoneUDID, anotherUDID]))
        let connectedDevice = try await ProfileRefreshSafety.renewalDeviceUDID(targets: [sharedProfile]) {
            anotherUDID.replacingOccurrences(of: "-", with: "").lowercased()
        }
        precondition(connectedDevice == anotherUDID)
        do {
            _ = try await ProfileRefreshSafety.renewalDeviceUDID(targets: [sharedProfile]) {
                "00008140-0003000000000003"
            }
            fatalError("accepted a connected device outside the installed profile")
        } catch let error as SelfRefreshError {
            guard case .backendOperationFailed = error else { throw error }
        }
        let installedSummary = try ProfileRefreshSafety.summary(for: targets)
        precondition(installedSummary.expirationDate == Date(timeIntervalSince1970:3_000_000_000))
        var events:[String] = []
        let result = try await ProfileRefreshSafety.refresh(targets:targets,download:{ id in
            events.append("download \(id)")
            return try data(id,expiry:id.hasSuffix("controls") ? 3_000_000_000 : 4_000_000_000)
        },install:{ bytes in
            events.append("install \(try ProfileInspector.parseMobileProvisionData(bytes).bundleIdentifier)")
        })
        precondition(events == ["download lab.receiver","download lab.receiver.controls","install lab.receiver","install lab.receiver.controls"])
        precondition(result.expirationDate == Date(timeIntervalSince1970:3_000_000_000))
        for failure in ["download", "team", "id", "certificate", "expired"] {
            var installs = 0
            do {
                _ = try await ProfileRefreshSafety.refresh(targets:targets,download:{ id in
                    guard id.hasSuffix("controls") else { return try data(id) }
                    switch failure {
                    case "download": throw ProbeError()
                    case "team": return try data(id,team:"OTHER")
                    case "id": return try data("different")
                    case "certificate": return try data(id,certificate:Data([2]))
                    default: return try data(id,expiry:1)
                    }
                },install:{ _ in installs += 1 })
                fatalError("accepted \(failure)")
            } catch { precondition(installs == 0) }
        }
        var installAttempts = 0
        do {
            _ = try await ProfileRefreshSafety.refresh(targets:targets,download:{ try data($0) },install:{ _ in
                installAttempts += 1
                if installAttempts == 2 { throw ProbeError() }
            })
            fatalError("reported success after install failure")
        } catch { precondition(installAttempts == 2) }
        try FileManager.default.removeItem(at:ext.appendingPathComponent("embedded.mobileprovision"))
        do {
            _ = try ProfileRefreshSafety.installedTargets(in:main)
            fatalError("silently skipped extension without profile")
        } catch {}
        print("profile refresh: discovery, preflight, certificates, expiry and partial-install failure passed")
    }
}
'''

class SelfRefreshSafetyTests(unittest.TestCase):
    def test_production_refresh_preflight(self):
        with tempfile.TemporaryDirectory() as tmp:
            harness = Path(tmp) / "Probe.swift"
            harness.write_text(SWIFT)
            binary = Path(tmp) / "probe"
            sources = [SDK / "Public/SelfRefreshConfiguration.swift", SDK / "Core/SelfRefreshKeychain.swift", SDK / "Core/ProfileInspector.swift", SDK / "Core/ProfileRefreshSafety.swift", SDK / "Core/BundleInstallIdentity.swift", harness]
            result = subprocess.run(["xcrun", "swiftc", "-parse-as-library", *map(str, sources), "-o", str(binary)], text=True, capture_output=True, env=os.environ.copy())
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            result = subprocess.run([str(binary)],text=True,capture_output=True)
            self.assertEqual(result.returncode,0,result.stdout+result.stderr)
            self.assertIn("partial-install failure passed",result.stdout)

if __name__ == "__main__":
    unittest.main()
