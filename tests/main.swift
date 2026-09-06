import Foundation
import CryptoKit
import Network

let server=LinkServer(secret:Data(repeating:0x11,count:16))
var timer:DispatchSourceTimer?
var index:UInt64=0
server.onReady={
    index=0
    let cfg: [String:Any] = ["rate":48000,"channels":2,"format":"float32le","delayMs":250,"version":1]
    server.send(1,try! JSONSerialization.data(withJSONObject:cfg))
    let t=DispatchSource.makeTimerSource(queue:.main)
    t.schedule(deadline:.now()+0.01,repeating:.nanoseconds(5_333_333))
    t.setEventHandler {
        if index>=(CommandLine.arguments.contains("--long") ? 48000*30 : 4096) { t.cancel(); timer=nil; return }
        var d=Data(); d.be(clockNS()); d.be(index); d.be(UInt32(256))
        for f in 0..<256 { for c in 0..<2 { var bits=Float(CommandLine.arguments.contains("--long") ? sin(Double(Int(index)+f)*0.0576)*0.01 : Double((Int(index)+f)*2+c)/8192.0-0.5).bitPattern.littleEndian; withUnsafeBytes(of:&bits) { d.append(contentsOf:$0) } } }
        server.send(2,d); index+=256
    }; timer=t; t.resume()
}
server.onCommand={ type,data in if type==6 { server.send(6,data) }; if type==7 { server.endSession("Test stop") }; if type==8 && CommandLine.arguments.contains("--long") { print(String(data:data,encoding:.utf8) ?? "stats"); fflush(stdout) } }
server.onDisconnect={ _ in timer?.cancel(); timer=nil }
try server.listen()
print("TEST SERVER READY"); fflush(stdout)
dispatchMain()
