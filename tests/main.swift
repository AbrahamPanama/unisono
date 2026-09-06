import Foundation
import CryptoKit
import Network

let macReserve:Int
if let position=CommandLine.arguments.firstIndex(of:"--reserve") {
    guard position+1<CommandLine.arguments.count,let value=Int(CommandLine.arguments[position+1]) else { fatalError("--reserve requires an integer number of milliseconds") }
    macReserve=value
} else { macReserve=500 }
let server=LinkServer(secret:Data(repeating:0x11,count:16))
var timer:DispatchSourceTimer?
var index:UInt64=0
var encoder:AACEncoder?
var flacEncoder:FLACEncoder?
var epoch:UInt64=0,holdUntil:UInt64=0
var held=[Data](),stallInjected=false
let boundary24:[Int64]=[-8388608,-8388607,-65537,-1,0,1,65537,8388606,8388607]
func sample24(frame:Int64,channel:Int64)->Float {
    let q=frame%4096<256 ? 0 : frame%4096<512 ? boundary24[Int((frame*2+channel)%9)] : ((frame*1103515245+channel*12345)&0xffffff)-0x800000
    return Float(q)/8388608
}
server.onReady={
    index=0;epoch=clockNS()+10_000_000;held=[];holdUntil=0
    let req=(try? JSONSerialization.jsonObject(with:server.captureRequest)) as? [String:Any] ?? [:]
    encoder=(req["codec"] as? String)=="aac-lc" ? try! AACEncoder(sourceRate:48000,bitRate:(req["bitrate"] as? Int)==160000 ? 160000 : 256000) : nil
    flacEncoder=(req["codec"] as? String)=="flac" && !CommandLine.arguments.contains("--no-flac") ? try! FLACEncoder(sourceRate:48000) : nil
    let pcm24=(req["testPattern"] as? String)=="pcm24"
    var cfg: [String:Any] = ["rate":48000,"channels":2,"format":"float32le","version":1]
    if let encoder=encoder { cfg["format"]="aac-lc";cfg["bitrate"]=encoder.bitRate;cfg["primingFrames"]=encoder.primingFrames }
    if let flacEncoder=flacEncoder { cfg["format"]="flac";cfg["bitDepth"]=flacEncoder.bitDepth;cfg["streamInfo"]=flacEncoder.streamInfo.base64EncodedString() }
    if let object=req["debug"] as? [String:Any], !CommandLine.arguments.contains("--legacy-debug") {
        guard let debug=try? DebugSettings.parse(object) else { server.endSession("Ajustes de depuración inválidos"); return }
        cfg["debug"]=debug.json
        cfg["delayMs"]=debug.reserve(macMilliseconds:macReserve,receiverMilliseconds:req["reserveMs"] as? Int)
    } else { cfg["delayMs"]=LatencySettings.negotiate(macMilliseconds:macReserve,receiverMilliseconds:req["reserveMs"] as? Int) }
    server.send(1,try! JSONSerialization.data(withJSONObject:cfg))
    let t=DispatchSource.makeTimerSource(queue:.main)
    t.schedule(deadline:.now()+0.01,repeating:.nanoseconds(5_333_333))
    t.setEventHandler {
        if index>=(CommandLine.arguments.contains("--long") ? 48000*90 : 4096) { t.cancel(); timer=nil; return }
        var d=Data(); d.be(epoch+index*1_000_000_000/48000); d.be(index); d.be(UInt32(256))
        for f in 0..<256 { for c in 0..<2 {
            let value=pcm24 ? sample24(frame:Int64(index)+Int64(f),channel:Int64(c)) : Float(CommandLine.arguments.contains("--long") ? sin(Double(Int(index)+f)*0.0576)*0.01 : Double((Int(index)+f)*2+c)/8192.0-0.5)
            var bits=value.bitPattern.littleEndian; withUnsafeBytes(of:&bits) { d.append(contentsOf:$0) }
        } }
        let packets=flacEncoder.map { try! $0.encode(d) } ?? encoder.map { try! $0.encode(d) } ?? [d]
        if CommandLine.arguments.contains("--jitter") && !stallInjected && index>=48000*3 { holdUntil=clockNS()+850_000_000;stallInjected=true }
        held.append(contentsOf:packets)
        if clockNS()>=holdUntil { for packet in held { server.send(flacEncoder != nil ? 11 : encoder != nil ? 10 : 2,packet) };held=[] }
        index+=256
    }; timer=t; t.resume()
}
server.onCommand={ type,data in
    if type==6 { server.send(6,data) }
    if type==7 { server.endSession("Test stop") }
    if type==8 && CommandLine.arguments.contains("--long") { print(String(data:data,encoding:.utf8) ?? "stats"); fflush(stdout) }
    // Fixture-only control: exercise the real authenticated Mac->Android type-12 path.
    if type==14 && CommandLine.arguments.contains("--debug-test") { server.send(12,data) }
}
server.onDisconnect={ _ in timer?.cancel(); timer=nil; encoder=nil; flacEncoder=nil; held=[] }
try server.listen()
print("TEST SERVER READY"); fflush(stdout)
dispatchMain()
