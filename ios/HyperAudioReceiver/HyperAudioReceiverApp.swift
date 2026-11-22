import SwiftUI
#if !HYPERAUDIO_SIDEINSTALLER
import SelfRefreshKit
#endif
import UIKit

@main
struct HyperAudioReceiverApp: App {
    #if !HYPERAUDIO_SIDEINSTALLER
    init() {
        SelfRefreshManager.shared.configure(
            SelfRefreshConfiguration(urlScheme: "hyperaudio", preferredVPN: .localDevVPN,
                                     requiresCertificateTakeover: false,
                                     hostLanguageProvider: { "zh-Hans" })
        )
    }
    #endif

    @Environment(\.scenePhase) private var phase
    @State private var automatic = ReceiverAutomaticPreparation()
    var body: some Scene {
        WindowGroup {
            ContentView()
                .onAppear { prepareAutomatically() }
                .onOpenURL { url in
                    guard url.scheme == "hyperaudio" else { return }
                    automatic.handleExplicitAction()
                    switch url.host {
                    case "start": Receiver.shared.start()
                    case "stop": Receiver.shared.stop()
                    case "check": Receiver.shared.runDiagnostics()
                    #if !HYPERAUDIO_SIDEINSTALLER
                    case "refresh": SelfRefreshManager.shared.handleOpenURL(url)
                    #endif
                    default: break
                    }
                }
                .onChange(of: phase) { next in
                    if next == .active {
                        prepareAutomatically()
                    } else if next == .background { automatic.leaveForeground() }
                }
        }
    }
    private func prepareAutomatically() {
        guard UserDefaults.standard.bool(forKey:"onboardingComplete06") else { return }
        let generation = automatic.schedule()
        DispatchQueue.main.asyncAfter(deadline: .now()+0.3) {
            if automatic.shouldPrepare(generation, foreground: UIApplication.shared.applicationState == .active) {
                Receiver.shared.enterForeground()
            }
        }
    }
}
