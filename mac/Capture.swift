import Foundation
import CoreAudio
import AVFoundation

func checkAudio(_ s: OSStatus,_ action:String) throws { if s != noErr { throw NSError(domain:"Unisono.Audio",code:Int(s),userInfo:[NSLocalizedDescriptionKey:"\(action) (Core Audio \(s)). Revisa el permiso de grabación de audio en Ajustes del Sistema."]) } }
func address(_ selector:AudioObjectPropertySelector) -> AudioObjectPropertyAddress { AudioObjectPropertyAddress(mSelector:selector,mScope:kAudioObjectPropertyScopeGlobal,mElement:kAudioObjectPropertyElementMain) }

final class Capture {
    var tap:AudioObjectID=0, aggregate:AudioObjectID=0
    var proc:AudioDeviceIOProcID?
    var ring:OpaquePointer?
    var timer:DispatchSourceTimer?
    var engine:AVAudioEngine?, player:AVAudioPlayerNode?
    var format:AVAudioFormat?
    var onPacket:((Data)->Void)?
    var onError:((String)->Void)?
    var rate=48000.0, frameIndex:UInt64=0
    var delay=0.25, trim=0.0, local=true
    var started=false, firstCapture:UInt64=0
    var scratch=[Float](repeating:0,count:8192)
    var seenOverflow:UInt64=0
    func start(delay:Double,trim:Double,local:Bool) throws {
        stop(); self.delay=delay; self.trim=trim; self.local=local
        do {
            var pid=getpid(); var pidSize=UInt32(MemoryLayout<AudioObjectID>.size); var own:AudioObjectID=0
            var pa=address(kAudioHardwarePropertyTranslatePIDToProcessObject)
            try checkAudio(AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject),&pa,UInt32(MemoryLayout<pid_t>.size),&pid,&pidSize,&own),"Identificar el proceso")
            guard own != 0 else { throw NSError(domain:"Audio",code:1,userInfo:[NSLocalizedDescriptionKey:"No se pudo excluir Unísono de la captura."]) }
            let desc=CATapDescription(stereoGlobalTapButExcludeProcesses:[own]); desc.name="Unísono audio"; desc.isPrivate=true; desc.muteBehavior = .mutedWhenTapped
            try checkAudio(AudioHardwareCreateProcessTap(desc,&tap),"Capturar audio del sistema")
            var asbd=AudioStreamBasicDescription(); var size=UInt32(MemoryLayout<AudioStreamBasicDescription>.size); var a=address(kAudioTapPropertyFormat)
            try checkAudio(AudioObjectGetPropertyData(tap,&a,0,nil,&size,&asbd),"Leer formato")
            guard asbd.mFormatID==kAudioFormatLinearPCM,asbd.mBitsPerChannel==32,asbd.mChannelsPerFrame==2,(asbd.mFormatFlags & kAudioFormatFlagIsFloat) != 0,(asbd.mFormatFlags & kAudioFormatFlagIsBigEndian)==0 else { throw NSError(domain:"Audio",code:2,userInfo:[NSLocalizedDescriptionKey:"Esta salida no entrega PCM flotante estéreo de 32 bits. Selecciona una salida estéreo en la Mac."]) }
            rate=asbd.mSampleRate
            guard rate>=8000,rate<=192000 else { throw NSError(domain:"Audio",code:3) }
            let props:[String:Any]=[kAudioAggregateDeviceNameKey:"Unísono Capture",kAudioAggregateDeviceUIDKey:UUID().uuidString,kAudioAggregateDeviceIsPrivateKey:true,kAudioAggregateDeviceTapAutoStartKey:true,kAudioAggregateDeviceTapListKey:[[kAudioSubTapUIDKey:desc.uuid.uuidString,kAudioSubTapDriftCompensationKey:true]]]
            try checkAudio(AudioHardwareCreateAggregateDevice(props as CFDictionary,&aggregate),"Crear dispositivo de captura")
            ring=ring_create(); guard let ring=ring else { throw NSError(domain:"Audio",code:4) }
            format=AVAudioFormat(standardFormatWithSampleRate:rate,channels:2)
            if local {
                let e=AVAudioEngine(), p=AVAudioPlayerNode(); e.attach(p); e.connect(p,to:e.mainMixerNode,format:format); try e.start(); engine=e; player=p
            }
            let callback:AudioDeviceIOProc={ _,_,input,time,_,_,context in
                if let context=context { ring_push(OpaquePointer(context),input,time.pointee.mHostTime) }; return noErr
            }
            try checkAudio(AudioDeviceCreateIOProcID(aggregate,callback,UnsafeMutableRawPointer(ring),&proc),"Preparar captura")
            try checkAudio(AudioDeviceStart(aggregate,proc),"Iniciar captura")
            let t=DispatchSource.makeTimerSource(queue:.main); t.schedule(deadline:.now(),repeating:.milliseconds(4),leeway:.milliseconds(1)); t.setEventHandler { [weak self] in self?.drain() }; timer=t; t.resume()
        } catch { stop(); throw error }
    }
    func drain() {
        guard let ring=ring else { return }
        if ring_overflows(ring)>seenOverflow { onError?("La captura se atrasó. Se detuvo para evitar pérdida silenciosa de audio."); return }
        var n:UInt32=0,host:UInt64=0
        var iterations=0
        while ring_pop(ring,&scratch,&n,&host) != 0 {
            iterations+=1; if iterations>128 { break }
            if host==0 { host=mach_absolute_time() }
            let ns=UInt64(AVAudioTime.seconds(forHostTime:host)*1e9)
            if firstCapture==0 { firstCapture=ns }
            let pts=firstCapture+UInt64(Double(frameIndex)/rate*1e9)
            if clockNS()>pts+UInt64(delay*1e9) { onError?("El audio llegó tarde. Prueba el perfil de 500 ms."); return }
            var packet=Data(); packet.be(pts); packet.be(frameIndex); packet.be(n)
            scratch.withUnsafeBytes { packet.append(contentsOf:$0.prefix(Int(n)*8)) }
            onPacket?(packet)
            guard self.ring == ring else { return }
            // Scheduling local audio uses the same source timeline sent to Android.
            if let p=player,let f=format,let b=AVAudioPCMBuffer(pcmFormat:f,frameCapacity:n) {
                b.frameLength=n
                for i in 0..<Int(n) { b.floatChannelData![0][i]=scratch[2*i]; b.floatChannelData![1][i]=scratch[2*i+1] }
                let outputLatency=engine?.outputNode.presentationLatency ?? 0
                if !started {
                    let startNS=Double(pts)/1e9+delay+trim-outputLatency
                    p.scheduleBuffer(b); p.play(at:AVAudioTime(hostTime:AVAudioTime.hostTime(forSeconds:startNS))); started=true
                } else { p.scheduleBuffer(b) }
            }
            frameIndex+=UInt64(n)
        }
    }
    func stop() {
        timer?.cancel(); timer=nil
        if aggregate != 0,let proc=proc { AudioDeviceStop(aggregate,proc); AudioDeviceDestroyIOProcID(aggregate,proc) }; proc=nil
        if aggregate != 0 { AudioHardwareDestroyAggregateDevice(aggregate); aggregate=0 }
        if tap != 0 { AudioHardwareDestroyProcessTap(tap); tap=0 }
        player?.stop(); engine?.stop(); player=nil; engine=nil
        if let ring=ring { ring_free(ring) }; ring=nil
        frameIndex=0; firstCapture=0; started=false; seenOverflow=0
    }
    deinit { stop() }
}
