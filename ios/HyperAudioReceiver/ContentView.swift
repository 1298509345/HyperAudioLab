import SwiftUI
import AVKit

struct RoutePicker: UIViewRepresentable {
    func makeUIView(context: Context) -> AVRoutePickerView {
        let view=AVRoutePickerView(); view.prioritizesVideoDevices=false
        view.tintColor=UIColor(HyperStyle.accent); return view
    }
    func updateUIView(_ uiView: AVRoutePickerView, context: Context) {}
}

struct ContentView: View {
    @ObservedObject private var control=Receiver.shared.control
    @AppStorage("onboardingComplete06") private var onboardingComplete=false
    @State private var showIntroduction=false
    @State private var tab=0
    var body: some View {
        TabView(selection:$tab) {
            NavigationStack { ReceiverHomeView() }
                .tabItem { Label("连接",systemImage:"dot.radiowaves.left.and.right") }.tag(0)
            NavigationStack { ReceiverDevicesView() }
                .tabItem { Label("设备",systemImage:"iphone.gen3.radiowaves.left.and.right") }.tag(1)
            NavigationStack { ReceiverSettingsView() }
                .tabItem { Label("设置",systemImage:"slider.horizontal.3") }.tag(2)
        }
        .tint(HyperStyle.accent)
        .onAppear { showIntroduction = !onboardingComplete }
        .sheet(isPresented:$showIntroduction) {
            ReceiverIntroductionView {
                onboardingComplete=true; showIntroduction=false; Receiver.shared.enterForeground()
            }.interactiveDismissDisabled()
        }
        .alert(item:$control.pending) { request in
            Alert(title:Text("允许首次连接？"),
                  message:Text("\(request.name) 请求连接。请确认是你刚操作的 Android 手机；允许后会记住配对。"),
                  primaryButton:.default(Text("允许"),action:{control.approve(id:request.id)}),
                  secondaryButton:.cancel(Text("拒绝"),action:{control.deny(id:request.id)}))
        }
    }
}
