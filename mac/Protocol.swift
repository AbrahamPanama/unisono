import Foundation
import CryptoKit
import Network

func clockNS() -> UInt64 { DispatchTime.now().uptimeNanoseconds }
extension Data {
    mutating func be<T: FixedWidthInteger>(_ x: T) { var v=x.bigEndian; Swift.withUnsafeBytes(of:&v) { append(contentsOf:$0) } }
    func integer<T: FixedWidthInteger>(_ offset: Int, _: T.Type) -> T { self.subdata(in:offset..<offset+MemoryLayout<T>.size).withUnsafeBytes { T(bigEndian:$0.loadUnaligned(as:T.self)) } }
    var hex: String { map { String(format:"%02x",$0) }.joined() }
    init?(hex: String) { guard hex.count==32 else { return nil }; var d=Data(); var i=hex.startIndex; while i<hex.endIndex { let end=hex.index(i,offsetBy:2); guard let b=UInt8(hex[i..<end],radix:16) else { return nil }; d.append(b); i=end }; self=d }
}
func randomBytes(_ n: Int) -> Data { var b=[UInt8](repeating:0,count:n); _=SecRandomCopyBytes(kSecRandomDefault,n,&b); return Data(b) }

// All network and lifecycle operations run on the main queue. Never used by the audio callback.
final class LinkServer {
    var listener: NWListener?
    var connection: NWConnection?
    var sessionKey: SymmetricKey?
    var secret: Data
    var captureRequest=Data()
    var ready=false
    var pending=0
    var sentBytes:UInt64=0
    var onReady: (() -> Void)?
    var onCommand: ((UInt8,Data) -> Void)?
    var onDisconnect: ((String) -> Void)?
    var generation=UUID()
    init(secret: Data) { self.secret=secret }
    func listen() throws {
        let tcp=NWProtocolTCP.Options(); tcp.noDelay=true; tcp.connectionTimeout=5
        let p=NWParameters(tls:nil,tcp:tcp); p.includePeerToPeer=false
        let l=try NWListener(using:p,on:45871)
        l.newConnectionHandler={ [weak self] c in self?.accept(c) }
        l.stateUpdateHandler={ [weak self] state in if case .failed(let e)=state { self?.disconnect("No se pudo abrir la red: \(e.localizedDescription)") } }
        listener=l; l.start(queue:.main)
    }
    func stop() { listener?.cancel(); listener=nil; disconnect("") }
    func disconnect(_ why: String) {
        let had=connection != nil; generation=UUID(); ready=false; captureRequest=Data(); sessionKey=nil; pending=0
        connection?.stateUpdateHandler=nil; connection?.cancel(); connection=nil
        if had || !why.isEmpty { onDisconnect?(why) }
    }
    func accept(_ c: NWConnection) {
        guard connection==nil else { c.cancel(); return }
        connection=c; generation=UUID(); let gen=generation
        let challenge=randomBytes(32)
        c.stateUpdateHandler={ [weak self] state in
            guard let self=self, self.generation==gen else { return }
            if case .ready=state {
                self.raw(challenge)
                self.read(32,gen:gen) { proof in
                    let key=SymmetricKey(data:self.secret)
                    guard HMAC<SHA256>.isValidAuthenticationCode(proof,authenticating:challenge,using:key) else { self.disconnect("Clave de emparejamiento incorrecta"); return }
                    self.sessionKey=SymmetricKey(data:Data(HMAC<SHA256>.authenticationCode(for:challenge+Data("audio-v1".utf8),using:key)))
                    self.ready=true; self.send(9,Data("Unisono/1".utf8)); self.receiveFrame(gen)
                }
            } else if case .failed(let e)=state { self.disconnect(e.localizedDescription) }
        }
        c.start(queue:.main)
        DispatchQueue.main.asyncAfter(deadline:.now()+8) { [weak self] in if let self=self, self.generation==gen, !self.ready { self.disconnect("Tiempo de emparejamiento agotado") } }
    }
    func read(_ n: Int,gen: UUID,done:@escaping(Data)->Void) {
        guard generation==gen else { return }
        connection?.receive(minimumIncompleteLength:n,maximumLength:n) { [weak self] data,_,end,error in
            guard let self=self,self.generation==gen else { return }
            guard let data=data,data.count==n,error==nil else { self.disconnect("Se perdió la conexión con el celular"); return }
            done(data)
        }
    }
    func receiveFrame(_ gen: UUID) {
        read(4,gen:gen) { [weak self] head in
            guard let self=self else { return }; let n=Int(head.integer(0,UInt32.self))
            guard n>=29,n<=65536 else { self.disconnect("Paquete no válido"); return }
            self.read(n,gen:gen) { body in
                do {
                    guard let key=self.sessionKey else { return }
                    let data=try AES.GCM.open(AES.GCM.SealedBox(combined:body),using:key)
                    guard let type=data.first else { throw NSError(domain:"Protocol",code:1) }
                    let payload=Data(data.dropFirst())
                    if type==3 && payload.count==8 { var out=payload; out.be(clockNS()); self.send(4,out) }
                    else if type==5 { self.captureRequest=payload; self.onReady?() }
                    else { self.onCommand?(type,payload) }
                    self.receiveFrame(gen)
                } catch { self.disconnect("No se pudo autenticar el audio") }
            }
        }
    }
    func raw(_ d: Data) { connection?.send(content:d,completion:.contentProcessed({ _ in })) }
    func endSession(_ reason:String) {
        guard ready else { disconnect(reason); return }
        send(7,Data(reason.utf8)) { [weak self] in self?.disconnect(reason) }
    }
    func send(_ type: UInt8,_ payload: Data=Data(),completion:(()->Void)?=nil) {
        guard ready,let key=sessionKey,let c=connection else { return }
        do {
            let body=try AES.GCM.seal(Data([type])+payload,using:key).combined!
            var frame=Data(); frame.be(UInt32(body.count)); frame.append(body)
            guard pending+frame.count<1_000_000 else { disconnect("La red se atrasó. Vuelve a conectar con un perfil más estable."); return }
            pending+=frame.count; sentBytes+=UInt64(frame.count); let gen=generation
            c.send(content:frame,completion:.contentProcessed { [weak self] err in
                guard let self=self,self.generation==gen else { return }; self.pending-=frame.count
                if let err=err { self.disconnect(err.localizedDescription) } else { completion?() }
            })
        } catch { disconnect("Error de cifrado") }
    }
}
