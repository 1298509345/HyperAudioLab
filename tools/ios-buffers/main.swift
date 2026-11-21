import Foundation
import AVFoundation
func check(_ v: @autoclosure () -> Bool, _ message: String) { if !v() { fatalError(message) } }
let format = AVAudioFormat(standardFormatWithSampleRate:48000,channels:2)!
let pool = AudioBufferPool(format:format,frames:240,capacity:2)
let a = pool.acquire()!, b = pool.acquire()!
check(a.buffer !== b.buffer,"leases cannot share an in-use buffer")
check(a.buffer.frameLength == 240 && a.buffer.format.channelCount == 2,"correct frame/channel shape")
check(pool.acquire() == nil,"pool is bounded while engine owns all leases")
check(pool.createdCount == 2 && pool.inUseCount == 2,"bounded allocation accounting")
a.buffer.floatChannelData![0][0] = 0.5
pool.release(a)
let c = pool.acquire()!
check(c.buffer === a.buffer,"consumed buffer should be reused")
check(pool.acquire() == nil,"double ownership prevented")
let newEpoch = AudioBufferPool(format:format,frames:240,capacity:1)
let current = newEpoch.acquire()!
newEpoch.release(a)
check(newEpoch.acquire() == nil,"stale/foreign epoch cannot release a current buffer")
pool.release(c); pool.release(c)
let d = pool.acquire()!
pool.release(c)
check(pool.acquire() == nil,"double release cannot duplicate free slots")
pool.release(d); pool.release(b)
for _ in 0..<10000 { let lease=pool.acquire()!; pool.release(lease) }
check(pool.createdCount == 2 && pool.inUseCount == 0,"long-running reuse cannot grow the pool")
newEpoch.release(current)
let mono = AudioBufferPool(format:AVAudioFormat(standardFormatWithSampleRate:48000,channels:1)!,frames:480,capacity:1)
let m=mono.acquire()!
check(m.buffer.frameLength == 480 && m.buffer.format.channelCount == 1,"mono shape after format transition")
mono.release(current)
check(mono.acquire() == nil,"stereo lease cannot release mono slot")
print("BUFFER_OWNERSHIP_CHECKS_PASSED")
