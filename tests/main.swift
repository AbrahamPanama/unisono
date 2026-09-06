import Foundation
import CryptoKit
import Network

let server=LinkServer(secret:Data(repeating:0x11,count:16))
var timer:DispatchSourceTimer?
var index:UInt64=0
var encoder:AACEncoder?
var epoch:UInt64=0,holdUntil:UInt64=0
var held=[Data](),stallInjected=false
server.onReady={
    index=0;epoch=clockNS()+10_000_000;held=[];holdUntil=0
    let req=(try? JSONSerialization.jsonObject(with:server.captureRequest)) as? [String:Any] ?? [:]
    encoder=(req["codec"] as? String)=="aac-lc" ? try! AACEncoder(sourceRate:48000,bitRate:(req["bitrate"] as? Int)==160000 ? 160000 : 256000) : nil
    var cfg: [String:Any] = ["rate":48000,"channels":2,"format":"float32le","delayMs":250,"version":1]
    if let encoder=encoder { cfg["format"]="aac-lc";cfg["bitrate"]=encoder.bitRate;cfg["primingFrames"]=encoder.primingFrames }
    cfg["delayMs"]=max(250,min(1000,req["reserveMs"] as? Int ?? 250))
    server.send(1,try! JSONSerialization.data(withJSONObject:cfg))
    let t=DispatchSource.makeTimerSource(queue:.main)
    t.schedule(deadline:.now()+0.01,repeating:.nanoseconds(5_333_333))
    t.setEventHandler {
        if index>=(CommandLine.arguments.contains("--long") ? 48000*90 : 4096) { t.cancel(); timer=nil; return }
        var d=Data(); d.be(epoch+index*1_000_000_000/48000); d.be(index); d.be(UInt32(256))
        for f in 0..<256 { for c in 0..<2 { var bits=Float(CommandLine.arguments.contains("--long") ? sin(Double(Int(index)+f)*0.0576)*0.01 : Double((Int(index)+f)*2+c)/8192.0-0.5).bitPattern.littleEndian; withUnsafeBytes(of:&bits) { d.append(contentsOf:$0) } } }
        let packets=encoder.map { try! $0.encode(d) } ?? [d]
        if CommandLine.arguments.contains("--jitter") && !stallInjected && index>=48000*3 { holdUntil=clockNS()+850_000_000;stallInjected=true }
        held.append(contentsOf:packets)
        if clockNS()>=holdUntil { for packet in held { server.send(encoder==nil ? 2 : 10,packet) };held=[] }
        index+=256
    }; timer=t; t.resume()
}
server.onCommand={ type,data in if type==6 { server.send(6,data) }; if type==7 { server.endSession("Test stop") }; if type==8 && CommandLine.arguments.contains("--long") { print(String(data:data,encoding:.utf8) ?? "stats"); fflush(stdout) } }
server.onDisconnect={ _ in timer?.cancel(); timer=nil }
try server.listen()
print("TEST SERVER READY"); fflush(stdout)
dispatchMain()
