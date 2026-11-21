// macOS AVFoundation microbenchmark, not an iPhone power measurement.
import Foundation
import AVFoundation
import Darwin
func cpu() -> Double {
    var t=timespec(); clock_gettime(CLOCK_PROCESS_CPUTIME_ID,&t)
    return Double(t.tv_sec)+Double(t.tv_nsec)/1_000_000_000
}
let count=200000, depth=12
for channels in [1,2] {
    let frames=480/channels
    let format=AVAudioFormat(standardFormatWithSampleRate:48000,channels:AVAudioChannelCount(channels))!
    var checksum:Float=0
    #if BENCH_POOL
    let pool=AudioBufferPool(format:format,frames:frames,capacity:depth+4)
    var ring=[AudioBufferPool.Lease?](repeating:nil,count:depth)
    #else
    var ring=[AVAudioPCMBuffer?](repeating:nil,count:depth)
    #endif
    let start=cpu()
    for n in 0..<count {
        let slot=n%depth
        #if BENCH_POOL
        if let old=ring[slot] { pool.release(old) }
        let lease=pool.acquire()!; let buffer=lease.buffer; ring[slot]=lease
        #else
        let buffer=AVAudioPCMBuffer(pcmFormat:format,frameCapacity:AVAudioFrameCount(frames))!
        buffer.frameLength=AVAudioFrameCount(frames); ring[slot]=buffer
        #endif
        buffer.floatChannelData![0][0]=Float(n%100)/100
        checksum += buffer.floatChannelData![0][0]
    }
    #if BENCH_POOL
    let created=pool.createdCount, variant="pool"
    #else
    let created=count, variant="baseline"
    #endif
    let result:[String:Any] = ["variant":variant,"channels":channels,"iterations":count,
                               "buffersCreated":created,"cpuSeconds":cpu()-start,"checksum":checksum]
    let data=try! JSONSerialization.data(withJSONObject:result,options:.sortedKeys)
    print(String(data:data,encoding:.utf8)!)
}
