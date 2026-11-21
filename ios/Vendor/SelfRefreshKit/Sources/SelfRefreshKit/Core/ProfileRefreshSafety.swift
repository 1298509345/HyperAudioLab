import Foundation

/// Preflight every installed bundle before installing any replacement profile.
/// Kept separate from Apple authentication and misagent for offline regression tests.
enum ProfileRefreshSafety {
    static func installedTargets(in mainURL: URL) throws -> [ProvisioningProfileMetadata] {
        let plugins = mainURL.appendingPathComponent("PlugIns", isDirectory: true)
        var urls = [mainURL]
        if FileManager.default.fileExists(atPath: plugins.path) {
            urls += try FileManager.default.contentsOfDirectory(at: plugins, includingPropertiesForKeys: nil)
                .filter { $0.pathExtension.lowercased() == "appex" }
                .sorted { $0.lastPathComponent < $1.lastPathComponent }
        }
        var identifiers = Set<String>()
        return try urls.map { url in
            let info = try Data(contentsOf: url.appendingPathComponent("Info.plist"))
            guard let plist = try PropertyListSerialization.propertyList(from: info, options: [], format: nil) as? [String: Any],
                  let identifier = plist["CFBundleIdentifier"] as? String,
                  !identifier.isEmpty, identifiers.insert(identifier).inserted,
                  let profile = ProfileInspector.inspectEmbeddedProfile(in: url),
                  profile.bundleIdentifier == identifier,
                  !profile.teamIdentifier.isEmpty,
                  !profile.certificateSHA1Fingerprints.isEmpty else {
                throw SelfRefreshError.backendOperationFailed("无法核对 \(url.lastPathComponent) 的现有签名，续期已中止。请用电脑重新安装完整 IPA。")
            }
            return profile
        }
    }

    static func summary(for profiles: [ProvisioningProfileMetadata]) throws -> ProvisioningProfileMetadata {
        guard var result = profiles.first else { throw SelfRefreshError.invalidProvisioningProfile }
        result.expirationDate = profiles.map(\.expirationDate).min()!
        return result
    }

    /// An RPPairing identifier names the pairing and cannot be registered for signing.
    static func renewalDeviceUDID(
        targets: [ProvisioningProfileMetadata],
        readConnectedUDID: () async throws -> String
    ) async throws -> String {
        let restrictions = targets.compactMap(\.provisionedDeviceIDs).filter { !$0.isEmpty }
        let commonDevices = (restrictions.first ?? []).filter { udid in
            restrictions.dropFirst().allSatisfy {
                BundleInstallIdentity.profileCoversDevice(deviceIDs: $0, udid: udid)
            }
        }
        if commonDevices.count == 1, let udid = commonDevices.first { return udid }

        let connectedUDID = try await readConnectedUDID()
        if restrictions.isEmpty { return connectedUDID }
        guard let registeredUDID = commonDevices.first(where: {
            BundleInstallIdentity.profileCoversDevice(deviceIDs: [$0], udid: connectedUDID)
        }) else {
            throw SelfRefreshError.backendOperationFailed("当前连接的 iPhone 不在主 App 与扩展的共同签名设备中。请确认配对文件来自本机。")
        }
        return registeredUDID
    }

    static func refresh(
        targets: [ProvisioningProfileMetadata],
        download: (String) async throws -> Data,
        install: (Data) async throws -> Void
    ) async throws -> ProvisioningProfileMetadata {
        guard !targets.isEmpty else { throw SelfRefreshError.invalidProvisioningProfile }
        var replacements: [(data: Data, metadata: ProvisioningProfileMetadata)] = []
        for original in targets {
            let data = try await download(original.bundleIdentifier)
            let replacement = try ProfileInspector.parseMobileProvisionData(data)
            let oldCertificates = Set(original.certificateSHA1Fingerprints.map { $0.uppercased() })
            let newCertificates = Set(replacement.certificateSHA1Fingerprints.map { $0.uppercased() })
            guard replacement.bundleIdentifier == original.bundleIdentifier,
                  replacement.teamIdentifier == original.teamIdentifier,
                  replacement.expirationDate > Date(),
                  replacement.creationDate <= Date(),
                  !oldCertificates.isEmpty, oldCertificates.isSubset(of: newCertificates) else {
                throw SelfRefreshError.backendOperationFailed("\(original.bundleIdentifier) 的新描述文件与现有签名不兼容或已过期，未安装任何新描述文件。请确认登录的是原开发者账户，必要时用电脑重新签名安装。")
            }
            replacements.append((data, replacement))
        }
        for replacement in replacements {
            try await install(replacement.data)
        }
        // The host summary must never outlive an embedded extension.
        return try summary(for: replacements.map { $0.metadata })
    }
}
