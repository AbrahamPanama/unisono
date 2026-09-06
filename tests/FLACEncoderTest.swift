import Foundation
import AVFoundation

@main struct FLACEncoderTest {
    static func pcm(_ samples:[Float],start:UInt64,rate:Int,origin:UInt64=123_456_789) -> Data {
        var data=Data(); data.be(origin+start*1_000_000_000/UInt64(rate)); data.be(start); data.be(UInt32(samples.count/2))
        for sample in samples { var bits=sample.bitPattern.littleEndian; withUnsafeBytes(of:&bits) { data.append(contentsOf:$0) } }
        return data
    }

    static func roundTrip(_ samples:[Float],expected:[Int32],rate:Int,directory:URL,name:String) throws {
        let encoder=try FLACEncoder(sourceRate:Double(rate))
        precondition(encoder.rate==rate && encoder.bitDepth==24 && encoder.framesPerPacket==1024 && encoder.streamInfo.count==34)
        var packets=[Data](),cursor=0,chunk=0
        let sizes=[1,17,255,1024,4096,33,511]
        while cursor<samples.count/2 {
            let frames=min(sizes[chunk%sizes.count],samples.count/2-cursor)
            packets+=try encoder.encode(pcm(Array(samples[cursor*2..<(cursor+frames)*2]),start:UInt64(cursor),rate:rate))
            cursor+=frames; chunk+=1
        }
        packets+=try encoder.finish()
        let finishedAgain=try encoder.finish(); precondition(finishedAgain.isEmpty)
        // The streaming metadata has unknown duration. A finite file needs its total sample count.
        var metadata=encoder.streamInfo
        let total=UInt64(samples.count/2)
        metadata[13]=(metadata[13]&0xf0)|UInt8((total>>32)&0x0f)
        for i in 14..<18 { metadata[i]=UInt8((total>>UInt64((17-i)*8))&0xff) }
        var stream=Data("fLaC".utf8); stream.append(contentsOf:[0x80,0,0,34]); stream.append(metadata)
        var index:UInt64=0
        for packet in packets {
            let count=packet.integer(16,UInt32.self)
            precondition(count>0 && count<=1024 && packet.count<=20+32768)
            precondition(packet.integer(8,UInt64.self)==index)
            precondition(packet.integer(0,UInt64.self)==123_456_789+index*1_000_000_000/UInt64(rate))
            precondition(packet[20]==0xff && packet[21]&0xfc==0xf8)
            index+=UInt64(count); stream.append(packet.dropFirst(20))
        }
        precondition(index==samples.count/2,"FLAC encoder lost or added frames")
        if samples.count/2%1024 != 0 { precondition(packets.last!.integer(16,UInt32.self)==samples.count/2%1024) }
        let fileURL=directory.appendingPathComponent(name+".flac")
        try stream.write(to:fileURL)
        // Use Apple's file reader independently of the streaming packet code. It supplies left-aligned Int32 PCM.
        let file=try AVAudioFile(forReading:fileURL,commonFormat:.pcmFormatInt32,interleaved:false)
        precondition(file.processingFormat.sampleRate==Double(rate) && file.processingFormat.channelCount==2 && file.length==samples.count/2)
        let buffer=AVAudioPCMBuffer(pcmFormat:file.processingFormat,frameCapacity:4096)!
        var decoded=0
        while decoded<file.length {
            try file.read(into:buffer)
            if buffer.frameLength==0 { break }
            for frame in 0..<Int(buffer.frameLength) { for channel in 0..<2 {
                let position=(decoded+frame)*2+channel
                precondition(position<expected.count,"FLAC decoder added samples")
                let value=buffer.int32ChannelData![channel][frame]
                precondition(value==expected[position]<<8,"FLAC changed a 24-bit sample at \(position): \(value>>8) != \(expected[position])")
            } }
            decoded+=Int(buffer.frameLength)
        }
        precondition(decoded*2==expected.count,"FLAC decoder lost samples")
        print("PASS: FLAC \(name), \(rate) Hz, \(decoded) stereo frames, exact 24-bit integers (\(stream.count) encoded bytes).")
    }

    static func rejects(_ label:String,_ action:() throws -> Void) {
        do { try action(); preconditionFailure("Accepted invalid FLAC input: \(label)") } catch {}
    }

    static func main() throws {
        let directory=FileManager.default.temporaryDirectory.appendingPathComponent("Unisono-FLAC-\(UUID().uuidString)",isDirectory:true)
        try FileManager.default.createDirectory(at:directory,withIntermediateDirectories:true)
        defer { try? FileManager.default.removeItem(at:directory) }
        // Values include the 24th bit and independent channel patterns. Every integer is exactly representable as Float32 / 2^23.
        var integers=[Int32]()
        for value in -8201..<8202 {
            integers.append(Int32((value*997)&0xffffff)-8388608)
            integers.append(Int32((value*1301+73)&0xffffff)-8388608)
        }
        let samples=integers.map { Float($0)/8388608 }
        for rate in [8000,44100,48000,96000,192000] {
            try roundTrip(samples,expected:integers,rate:rate,directory:directory,name:"stereo-\(rate)")
        }
        try roundTrip([Float](repeating:0,count:2050),expected:[Int32](repeating:0,count:2050),rate:48000,directory:directory,name:"silence-short-tail")
        let edges:[Float]=[-2,-1,-1+1/8388608,Float.leastNonzeroMagnitude,-Float.leastNonzeroMagnitude,
            -0.5/8388608,0.5/8388608,-1.5/8388608,1.5/8388608,0,1-1/8388608,1,2,-0.25,0.25,0]
        let quantized:[Int32]=[-8388608,-8388608,-8388607,0,0,-1,1,-2,2,0,8388607,8388607,8388607,-2097152,2097152,0]
        // Native FLAC requires one full block before it can emit a partial final block.
        let edgeSamples=Array(repeating:edges,count:128).flatMap{$0}+Array(edges.prefix(2))
        let edgeIntegers=Array(repeating:quantized,count:128).flatMap{$0}+Array(quantized.prefix(2))
        try roundTrip(edgeSamples,expected:edgeIntegers,rate:48000,directory:directory,name:"clipping-rounding-boundaries")
        var random:UInt32=0x12345678, noise=[Int32]()
        for _ in 0..<8194 { random ^= random<<13; random ^= random>>17; random ^= random<<5; noise.append(Int32(random&0xffffff)-8388608) }
        try roundTrip(noise.map{Float($0)/8388608},expected:noise,rate:48000,directory:directory,name:"incompressible-24bit")
        for invalid:Double in [.nan,.infinity,0,7999,192001,48000.5] { rejects("rate") { _=try FLACEncoder(sourceRate:invalid) } }
        let encoder=try FLACEncoder(sourceRate:48000)
        rejects("short header") { _=try encoder.encode(Data(repeating:0,count:19)) }
        rejects("zero frames") { _=try encoder.encode(pcm([],start:0,rate:48000)) }
        rejects("oversized block") { _=try encoder.encode(pcm([Float](repeating:0,count:8194),start:0,rate:48000)) }
        var truncated=pcm([0,0],start:0,rate:48000); truncated.removeLast()
        rejects("truncated payload") { _=try encoder.encode(truncated) }
        for value:Float in [.nan,.infinity,-.infinity] { rejects("nonfinite sample") { _=try encoder.encode(pcm([0,value],start:0,rate:48000)) } }
        _=try encoder.encode(pcm([0,0],start:0,rate:48000))
        rejects("missing frames") { _=try encoder.encode(pcm([0,0],start:2,rate:48000)) }
        _=try encoder.encode(pcm([0,0],start:1,rate:48000))
        let remaining=try encoder.encode(pcm([Float](repeating:0,count:2044),start:2,rate:48000))
        let final=remaining+(try encoder.finish()); precondition(final.count==1 && final[0].integer(16,UInt32.self)==1024)
        rejects("encode after finish") { _=try encoder.encode(pcm([0,0],start:1024,rate:48000)) }
        let short=try FLACEncoder(sourceRate:48000); _=try short.encode(pcm([0,0],start:0,rate:48000))
        rejects("native stream shorter than a full block must not silently lose samples") { _=try short.finish() }
        print("PASS: FLAC rejects nonfinite/malformed input, invalid rates and discontinuous samples; no priming or final-frame loss.")
    }
}
