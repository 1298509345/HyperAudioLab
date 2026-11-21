import Foundation

struct AudioPacket {
    let sequence: UInt32
    let pcm: Data
    let channels: Int
    var frames: Int { 480 / channels }
    func sample(frame: Int, channel: Int) -> Float {
        guard frame >= 0, frame < frames, channel >= 0, channel < channels else { return 0 }
        let offset = (frame * channels + channel) * 2
        return Float(Int16(bitPattern: UInt16(pcm[offset]) | UInt16(pcm[offset+1]) << 8))/32768
    }
    // HAP1 | big-endian u32 sequence | big-endian u64 Android monotonic ns |
    // 8 ASCII lower-case hex token | 480 signed 16-bit little-endian samples.
    static func decode(_ data: Data, token: String) -> AudioPacket? {
        guard data.count == 984 else { return nil }
        let b = [UInt8](data)
        let magic = Array(b[0..<4])
        guard magic == Array("HAP1".utf8) || magic == Array("HAP2".utf8),
              Array(b[16..<24]) == Array(token.utf8) else { return nil }
        let seq = b[4..<8].reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
        return AudioPacket(sequence: seq, pcm: Data(b[24..<984]), channels: magic == Array("HAP2".utf8) ? 2 : 1)
    }
}
