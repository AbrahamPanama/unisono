import Foundation
import AVFoundation

/// Native AAC-LC encoder. Runs while draining capture, never in the Core Audio callback.
final class AACEncoder {
    let input:AVAudioFormat, output:AVAudioFormat, converter:AVAudioConverter
    let rate=48000, bitRate:Int
    var index:UInt64=0, origin:UInt64=0
    var primingFrames:Int { Int(converter.primeInfo.leadingFrames) }
    init(sourceRate:Double,bitRate:Int) throws {
        self.bitRate=bitRate
        guard let input=AVAudioFormat(standardFormatWithSampleRate:sourceRate,channels:2),
              let output=AVAudioFormat(settings:[AVFormatIDKey:kAudioFormatMPEG4AAC,AVSampleRateKey:48000,AVNumberOfChannelsKey:2,AVEncoderBitRateKey:bitRate]),
              let converter=AVAudioConverter(from:input,to:output) else { throw NSError(domain:"AAC",code:1) }
        self.input=input; self.output=output; self.converter=converter; converter.bitRate=bitRate
    }
    func encode(_ pcm:Data) throws -> [Data] {
        guard pcm.count>=20 else { throw NSError(domain:"AAC",code:2) }
        let frames=Int(pcm.integer(16,UInt32.self))
        guard frames>0,frames<=4096,pcm.count==20+frames*8 else { throw NSError(domain:"AAC",code:3) }
        if origin==0 { origin=pcm.integer(0,UInt64.self) }
        var offset=0, packets=[Data]()
        for _ in 0..<32 {
            let out=AVAudioCompressedBuffer(format:output,packetCapacity:8,maximumPacketSize:converter.maximumOutputPacketSize)
            var error:NSError?
            let status=converter.convert(to:out,error:&error) { requested,state in
                guard offset<frames else { state.pointee = .noDataNow; return nil }
                let n=min(Int(requested),frames-offset)
                guard n>0,let b=AVAudioPCMBuffer(pcmFormat:self.input,frameCapacity:AVAudioFrameCount(n)) else { state.pointee = .noDataNow; return nil }
                b.frameLength=AVAudioFrameCount(n)
                pcm.withUnsafeBytes { raw in
                    for f in 0..<n { for c in 0..<2 {
                        let bits=UInt32(littleEndian:raw.loadUnaligned(fromByteOffset:20+((offset+f)*2+c)*4,as:UInt32.self))
                        b.floatChannelData![c][f]=Float(bitPattern:bits)
                    } }
                }
                offset+=n; state.pointee = .haveData; return b
            }
            if let error=error { throw error }
            guard status != .error else { throw NSError(domain:"AAC",code:4) }
            for i in 0..<Int(out.packetCount) {
                guard let desc=out.packetDescriptions?[i],desc.mDataByteSize>0 else { throw NSError(domain:"AAC",code:5) }
                var packet=Data(); packet.be(origin+UInt64(max(0,Int64(index)-Int64(primingFrames)))*1_000_000_000/UInt64(rate)); packet.be(index); packet.be(UInt32(1024))
                packet.append(out.data.advanced(by:Int(desc.mStartOffset)).assumingMemoryBound(to:UInt8.self),count:Int(desc.mDataByteSize)); packets.append(packet); index+=1024
            }
            if status == .inputRanDry || status == .endOfStream { return packets }
        }
        throw NSError(domain:"AAC",code:6,userInfo:[NSLocalizedDescriptionKey:"El codificador de audio no respondió"])
    }
}
