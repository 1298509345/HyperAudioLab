import Foundation
import AVFoundation

/// Serialized by the receiver queue. Release only after dataConsumed, never on scheduling.
final class AudioBufferPool {
    struct Lease {
        fileprivate let owner: UUID
        fileprivate let index: Int
        fileprivate let ticket: UInt64
        let buffer: AVAudioPCMBuffer
    }
    private let owner=UUID()
    private let format: AVAudioFormat
    private let frames: AVAudioFrameCount
    let capacity: Int
    private var buffers: [AVAudioPCMBuffer] = []
    private var tickets: [UInt64] = []
    private var active: [Bool] = []
    private var available: [Int] = []
    private var counter: UInt64 = 0
    private(set) var inUseCount = 0
    var createdCount: Int { buffers.count }
    init(format: AVAudioFormat, frames: Int, capacity: Int) {
        self.format=format; self.frames=AVAudioFrameCount(max(1,frames))
        self.capacity=max(1,min(64,capacity))
        buffers.reserveCapacity(self.capacity); tickets.reserveCapacity(self.capacity)
        active.reserveCapacity(self.capacity); available.reserveCapacity(self.capacity)
    }
    func acquire() -> Lease? {
        let index: Int
        if let free=available.popLast() { index=free }
        else {
            guard buffers.count < capacity,
                  let buffer=AVAudioPCMBuffer(pcmFormat:format,frameCapacity:frames) else { return nil }
            buffer.frameLength=frames
            index=buffers.count; buffers.append(buffer); tickets.append(0); active.append(false)
        }
        counter &+= 1; tickets[index]=counter; active[index]=true; inUseCount += 1
        return Lease(owner:owner,index:index,ticket:counter,buffer:buffers[index])
    }
    func release(_ lease: Lease) {
        guard lease.owner == owner, buffers.indices.contains(lease.index),
              active[lease.index], tickets[lease.index] == lease.ticket else { return }
        active[lease.index]=false; inUseCount -= 1; available.append(lease.index)
    }
}
