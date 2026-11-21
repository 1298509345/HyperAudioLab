import Foundation

@main
struct LiveStateProbe {
    static func main() async throws {
        let arguments = CommandLine.arguments
        if arguments[1] == "namespace" {
            let bundle = Bundle(url: URL(fileURLWithPath: arguments[2]))!
            print(ReceiverControlChannel.forBundle(bundle).namespace)
            return
        }
        let channel = ReceiverControlChannel(namespace: arguments[2])
        if arguments[1] == "read" {
            print(try await channel.currentValue() ? "on" : "off")
        } else {
            let publisher = ReceiverControlStatePublisher(channel: channel)
            precondition(publisher.isRegistered)
            try Data("ready".utf8).write(to: URL(fileURLWithPath: arguments[4]))
            // Deliberately block the owner main queue: readers must still get real state.
            while let command = readLine() {
                precondition(publisher.publish(command == "on"))
                print("ACK")
                fflush(stdout)
            }
            withExtendedLifetime(publisher) {}
        }
    }
}
