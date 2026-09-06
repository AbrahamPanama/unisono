import Foundation
import AVFoundation

/// Native, unprimed FLAC with 1024-frame blocks. Runs off the capture callback.
/// Float32 is rounded to signed 24-bit PCM first; FLAC preserves those integers exactly.
final class FLACEncoder {
    let rate:Int
    let bitDepth=24
    let framesPerPacket=1024
    let streamInfo:Data
    private let input:AVAudioFormat, output:AVAudioFormat, converter:AVAudioConverter
    private var origin:UInt64?, originIndex:UInt64=0, encodedIndex:UInt64=0, inputIndex:UInt64=0
    private var finished=false

    init(sourceRate:Double) throws {
        guard sourceRate.isFinite, sourceRate>=8000, sourceRate<=192000,
              sourceRate.rounded()==sourceRate else { throw Self.failure("Frecuencia FLAC no compatible") }
        rate=Int(sourceRate)
        // Int32 input is left-aligned Q31. Discarding its eight zero low bits is exact.
        var description=AudioStreamBasicDescription(mSampleRate:sourceRate,mFormatID:kAudioFormatFLAC,
            mFormatFlags:0,mBytesPerPacket:0,mFramesPerPacket:1024,mBytesPerFrame:0,
            mChannelsPerFrame:2,mBitsPerChannel:24,mReserved:0)
        guard let input=AVAudioFormat(commonFormat:.pcmFormatInt32,sampleRate:sourceRate,channels:2,interleaved:false),
              let output=AVAudioFormat(streamDescription:&description),
              let converter=AVAudioConverter(from:input,to:output),
              let cookie=converter.magicCookie else { throw Self.failure("El codificador FLAC no está disponible") }
        let info=try Self.extractStreamInfo(cookie)
        var formatBits:UInt64=0
        for byte in info[10..<18] { formatBits=(formatBits<<8)|UInt64(byte) }
        guard Int((formatBits>>44)&0xfffff)==rate, ((formatBits>>41)&7)+1==2,
              ((formatBits>>36)&31)+1==24,
              info.integer(0,UInt16.self)==1024, info.integer(2,UInt16.self)==1024,
              converter.maximumOutputPacketSize>0, converter.maximumOutputPacketSize<=32768,
              converter.primeInfo.leadingFrames==0 else { throw Self.failure("El codificador no ofrece FLAC de 24 bits sin retardo inicial") }
        self.input=input; self.output=output; self.converter=converter; streamInfo=info
    }

    func encode(_ pcm:Data) throws -> [Data] {
        guard !finished, pcm.count>=20 else { throw Self.failure("Bloque PCM FLAC no válido") }
        let frames=Int(pcm.integer(16,UInt32.self)), start=pcm.integer(8,UInt64.self)
        guard frames>0, frames<=4096, pcm.count==20+frames*8,
              start<=UInt64.max-UInt64(frames), origin==nil || start==inputIndex else {
            throw Self.failure("Secuencia PCM FLAC no válida")
        }
        // Validate the entire block before consuming anything, so NaN/Infinity never reach the codec.
        var samples=[Int32](repeating:0,count:frames*2)
        try pcm.withUnsafeBytes { raw in
            for i in samples.indices {
                let bits=UInt32(littleEndian:raw.loadUnaligned(fromByteOffset:20+i*4,as:UInt32.self))
                let value=Float(bitPattern:bits)
                guard value.isFinite else { throw Self.failure("Muestra PCM FLAC no finita") }
                let scaled=(Double(max(-1,min(1,value)))*8388608).rounded(.toNearestOrAwayFromZero)
                let integer=Int32(max(-8388608,min(8388607,scaled)))
                samples[i]=integer<<8
            }
        }
        if origin==nil {
            origin=pcm.integer(0,UInt64.self); originIndex=start; encodedIndex=start; inputIndex=start
        }
        inputIndex+=UInt64(frames)
        var offset=0
        let packets=try convert { requested,state in
            guard offset<frames else { state.pointee = .noDataNow; return nil }
            let n=min(Int(requested),frames-offset)
            guard n>0, let buffer=AVAudioPCMBuffer(pcmFormat:self.input,frameCapacity:AVAudioFrameCount(n)) else {
                state.pointee = .noDataNow; return nil
            }
            buffer.frameLength=AVAudioFrameCount(n)
            for f in 0..<n { for channel in 0..<2 { buffer.int32ChannelData![channel][f]=samples[(offset+f)*2+channel] } }
            offset+=n; state.pointee = .haveData; return buffer
        }
        guard offset==frames else { throw Self.failure("FLAC no consumió el bloque PCM completo") }
        return packets
    }

    /// Drains the final short block after at least one full block. Apple's encoder cannot emit a
    /// whole stream shorter than 1024 frames; report it instead of silently losing those samples.
    /// Live stop deliberately discards its tail.
    func finish() throws -> [Data] {
        if finished { return [] }
        finished=true
        guard origin != nil else { return [] }
        let packets=try convert { _,state in state.pointee = .endOfStream; return nil }
        guard encodedIndex==inputIndex else { throw Self.failure("FLAC terminó con muestras pendientes") }
        return packets
    }

    private func convert(_ supply:@escaping AVAudioConverterInputBlock) throws -> [Data] {
        var packets=[Data]()
        for _ in 0..<32 {
            let buffer=AVAudioCompressedBuffer(format:output,packetCapacity:8,maximumPacketSize:converter.maximumOutputPacketSize)
            var error:NSError?
            let status=converter.convert(to:buffer,error:&error,withInputFrom:supply)
            if let error=error { throw error }
            guard status != .error else { throw Self.failure("No se pudo codificar FLAC") }
            for i in 0..<Int(buffer.packetCount) {
                guard let description=buffer.packetDescriptions?[i] else { throw Self.failure("Paquete FLAC sin descripción") }
                let frames=description.mVariableFramesInPacket
                guard frames>0, frames<=framesPerPacket, description.mDataByteSize>0,
                      description.mDataByteSize<=32768, description.mStartOffset>=0,
                      UInt64(description.mStartOffset)+UInt64(description.mDataByteSize)<=UInt64(buffer.byteLength),
                      encodedIndex<=inputIndex, UInt64(frames)<=inputIndex-encodedIndex,
                      let origin=origin else { throw Self.failure("Paquete FLAC no válido") }
                let elapsed=encodedIndex-originIndex
                // Split whole seconds to avoid overflowing during long sessions.
                let seconds=elapsed/UInt64(rate), remainder=elapsed%UInt64(rate)
                guard seconds<=UInt64.max/1_000_000_000 else { throw Self.failure("Sesión FLAC demasiado larga") }
                let secondsNS=seconds*1_000_000_000, remainderNS=remainder*1_000_000_000/UInt64(rate)
                guard secondsNS<=UInt64.max-remainderNS else { throw Self.failure("Tiempo FLAC no válido") }
                let delta=secondsNS+remainderNS
                guard origin<=UInt64.max-delta else { throw Self.failure("Tiempo FLAC no válido") }
                var packet=Data(); packet.be(origin+delta); packet.be(encodedIndex); packet.be(frames)
                packet.append(buffer.data.advanced(by:Int(description.mStartOffset)).assumingMemoryBound(to:UInt8.self),count:Int(description.mDataByteSize))
                packets.append(packet); encodedIndex+=UInt64(frames)
            }
            if status == .inputRanDry || status == .endOfStream { return packets }
        }
        throw Self.failure("El codificador FLAC no respondió")
    }

    private static func extractStreamInfo(_ cookie:Data) throws -> Data {
        // Apple's magic cookie is an ISO-BMFF dfLa atom; Android needs the raw STREAMINFO block.
        if cookie.count==50, String(data:cookie.subdata(in:4..<8),encoding:.ascii)=="dfLa",
           cookie.integer(0,UInt32.self)==50, cookie[12]&0x7f==0,
           cookie[13]==0, cookie[14]==0, cookie[15]==34 { return cookie.subdata(in:16..<50) }
        if cookie.count==42, String(data:cookie.prefix(4),encoding:.ascii)=="fLaC",
           cookie[4]&0x7f==0, cookie[5]==0, cookie[6]==0, cookie[7]==34 { return cookie.subdata(in:8..<42) }
        throw failure("Metadatos FLAC no compatibles")
    }

    private static func failure(_ message:String) -> NSError {
        NSError(domain:"FLAC",code:1,userInfo:[NSLocalizedDescriptionKey:message])
    }
}
