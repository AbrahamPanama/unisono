import AppKit
import Foundation
import CryptoKit
import Darwin
import CoreImage

let mint=NSColor(srgbRed:0.49,green:0.81,blue:0.62,alpha:1)
let muted=NSColor(white:0.65,alpha:1)
final class MintSliderCell:NSSliderCell {
    override func drawBar(inside rect:NSRect,flipped:Bool) {
        let bar=NSRect(x:rect.minX,y:rect.midY-2.5,width:rect.width,height:5)
        NSColor(white:0.25,alpha:1).setFill(); NSBezierPath(roundedRect:bar,xRadius:2.5,yRadius:2.5).fill()
        if isEnabled { mint.setFill(); var filled=bar; filled.size.width*=CGFloat((doubleValue-minValue)/(maxValue-minValue)); NSBezierPath(roundedRect:filled,xRadius:2.5,yRadius:2.5).fill() }
    }
}
final class Surface:NSView {
    override var isFlipped:Bool { true }
    override init(frame:NSRect) { super.init(frame:frame); wantsLayer=true; layer?.backgroundColor=NSColor(srgbRed:0.10,green:0.11,blue:0.11,alpha:1).cgColor }
    required init?(coder:NSCoder) { fatalError() }
}
func label(_ text:String,_ size:CGFloat,_ weight:NSFont.Weight = .regular,_ color:NSColor = .labelColor) -> NSTextField {
    let l=NSTextField(wrappingLabelWithString:text); l.font = .systemFont(ofSize:size,weight:weight); l.textColor=color; l.isSelectable=false; return l
}
func ips() -> [String] {
    var p:UnsafeMutablePointer<ifaddrs>?; guard getifaddrs(&p)==0 else { return [] }; defer { freeifaddrs(p) }
    var out=[String](); var q=p
    while let a=q { defer { q=a.pointee.ifa_next }; guard let sa=a.pointee.ifa_addr,sa.pointee.sa_family==UInt8(AF_INET),String(cString:a.pointee.ifa_name).hasPrefix("en") else { continue }; var s=[CChar](repeating:0,count:Int(NI_MAXHOST)); if getnameinfo(sa,socklen_t(sa.pointee.sa_len),&s,socklen_t(s.count),nil,0,NI_NUMERICHOST)==0 { out.append(String(cString:s)) } }
    return out
}

final class App:NSObject,NSApplicationDelegate {
    var item:NSStatusItem!, pop=NSPopover(), settings:NSWindow?
    let capture=Capture()
    var server:LinkServer!
    var stateLabel:NSTextField!, deviceLabel:NSTextField!, qualityLabel:NSTextField!, detailLabel:NSTextField!, mainButton:NSButton!, volume:NSSlider!
    var linkField:NSTextField!, delayPicker:NSPopUpButton!, trimField:NSTextField!, localSwitch:NSButton!
    var qrView:NSImageView!
    var streaming=false, lastHeartbeat=clockNS(), watchdog:Timer?
    var secret=Data()
    var delay:Double { let v=UserDefaults.standard.double(forKey:"delay"); return v>=0.12 && v<=1 ? v : 0.25 }
    var trim:Double { max(-0.1,min(0.1,UserDefaults.standard.double(forKey:"trim"))) }
    var local:Bool { UserDefaults.standard.object(forKey:"local")==nil || UserDefaults.standard.bool(forKey:"local") }
    func applicationDidFinishLaunching(_ notification:Notification) {
        NSApp.setActivationPolicy(.accessory); NSApp.appearance=NSAppearance(named:.darkAqua)
        if let saved=UserDefaults.standard.string(forKey:"pairKey"),let d=Data(hex:saved) { secret=d } else { secret=randomBytes(16); UserDefaults.standard.set(secret.hex,forKey:"pairKey") }
        item=NSStatusBar.system.statusItem(withLength:NSStatusItem.squareLength)
        item.button?.image=NSImage(systemSymbolName:"waveform",accessibilityDescription:"Unísono"); item.button?.target=self; item.button?.action=#selector(toggle)
        item.button?.toolTip="Unísono — Audio compartido"
        pop.behavior = .transient; pop.contentSize=NSSize(width:360,height:558)
        let vc=NSViewController(); vc.view=makePanel(); pop.contentViewController=vc
        server=LinkServer(secret:secret)
        server.onReady={ [weak self] in self?.startAudio() }
        server.onDisconnect={ [weak self] why in self?.stopAudio(message:why.isEmpty ? "Listo para conectar" : why) }
        server.onCommand={ [weak self] type,payload in
            guard let self=self else { return }
            if type==7 { self.server.disconnect("") }
            if type==8 { self.lastHeartbeat=clockNS(); if let obj=(try? JSONSerialization.jsonObject(with:payload)) as? [String:Any] {
                let error=(obj["syncMs"] as? Double).map { String(format:"Desfase estimado: %+.0f ms",$0) } ?? "Midiendo sincronización…"
                self.detailLabel.stringValue=error
                if let name=obj["device"] as? String { self.deviceLabel.stringValue="Mac +\n"+String(name.prefix(28)) }
            } }
        }
        capture.onPacket={ [weak self] p in self?.server.send(2,p) }
        capture.onError={ [weak self] e in self?.server.disconnect(e) }
        if !CommandLine.arguments.contains("--preview") && !CommandLine.arguments.contains("--preview-settings") { do { try server.listen() } catch { stopAudio(message:error.localizedDescription) } }
        watchdog=Timer.scheduledTimer(withTimeInterval:1,repeats:true) { [weak self] _ in
            guard let self=self else { return }; if self.streaming && clockNS()-self.lastHeartbeat>8_000_000_000 { self.server.disconnect("El celular dejó de responder. Conéctalo de nuevo.") }
        }
        NSWorkspace.shared.notificationCenter.addObserver(self,selector:#selector(sleeping),name:NSWorkspace.willSleepNotification,object:nil)
        var a=address(kAudioHardwarePropertyDefaultOutputDevice)
        AudioObjectAddPropertyListenerBlock(AudioObjectID(kAudioObjectSystemObject),&a,.main) { [weak self] _,_ in if self?.streaming==true { self?.server.disconnect("Cambió la salida de la Mac. Vuelve a conectar.") } }
        if CommandLine.arguments.contains("--preview") { preview() }
        else if CommandLine.arguments.contains("--preview-settings") { secret=Data(repeating:0x11,count:16); openSettings(); snapshot(settings!) }
        else if CommandLine.arguments.contains("--open") { DispatchQueue.main.asyncAfter(deadline:.now()+0.5) { self.toggle() } }
    }
    func makePanel()->NSView {
        let v=Surface(frame:NSRect(x:0,y:0,width:360,height:558))
        func put(_ sub:NSView,_ x:CGFloat,_ y:CGFloat,_ w:CGFloat,_ h:CGFloat) { sub.frame=NSRect(x:x,y:y,width:w,height:h); v.addSubview(sub) }
        func line(_ y:CGFloat) { let b=NSBox(); b.boxType = .separator; put(b,0,y,360,1) }
        put(label("Unísono",24,.semibold),28,23,245,35)
        let gear=NSButton(image:NSImage(systemSymbolName:"gearshape",accessibilityDescription:"Configuración")!,target:self,action:#selector(openSettings)); gear.isBordered=false; put(gear,305,24,28,28)
        line(82)
        stateLabel=label("●  Listo para conectar",15,.medium,mint); put(stateLabel,28,104,304,24)
        deviceLabel=label("Mac +\nTu Android",28,.semibold); put(deviceLabel,28,144,305,78)
        put(label("Audio en ambos dispositivos",16,.regular,NSColor(white:0.76,alpha:1)),28,228,310,25)
        qualityLabel=label("PCM sin compresión · Perfil estable",13,.regular,muted); put(qualityLabel,28,264,305,24)
        line(300)
        let speaker=NSImageView(image:NSImage(systemSymbolName:"speaker.wave.2.fill",accessibilityDescription:nil)!); put(speaker,28,320,23,23)
        put(label("Volumen del celular",17),60,319,266,27)
        volume=NSSlider(); volume.cell=MintSliderCell(); volume.minValue=0; volume.maxValue=1; volume.doubleValue=0.6; volume.target=self; volume.action=#selector(changeVolume); volume.isContinuous=true; volume.isEnabled=false; put(volume,27,355,307,27)
        mainButton=NSButton(title:"Conectar celular",target:self,action:#selector(primary)); mainButton.bezelStyle = .rounded; mainButton.isBordered=false; mainButton.wantsLayer=true; mainButton.layer?.backgroundColor=mint.cgColor; mainButton.layer?.cornerRadius=9; mainButton.font = .systemFont(ofSize:18,weight:.medium); mainButton.contentTintColor=NSColor(white:0.05,alpha:1); put(mainButton,26,398,308,48)
        line(466)
        let change=NSButton(title:"Cambiar dispositivo                         ›",target:self,action:#selector(openSettings)); change.isBordered=false; change.alignment = .left; change.font = .systemFont(ofSize:15); put(change,27,480,307,26)
        line(517)
        let quit=NSButton(title:"Salir de Unísono",target:self,action:#selector(quitApp)); quit.isBordered=false; quit.font = .systemFont(ofSize:14); quit.contentTintColor=muted; quit.alignment = .left; put(quit,27,527,220,25)
        detailLabel=label("",12,.regular,muted)
        return v
    }
    @objc func toggle() { if pop.isShown { pop.performClose(nil) } else if let b=item.button { NSApp.activate(ignoringOtherApps:true); pop.show(relativeTo:b.bounds,of:b,preferredEdge:.minY) } }
    @objc func primary() { if streaming { server.endSession("Transmisión detenida desde la Mac") } else { openSettings() } }
    @objc func changeVolume() { var d=Data(); d.be(volume.floatValue.bitPattern); server.send(6,d) }
    @objc func quitApp() { capture.stop(); server.stop(); NSApp.terminate(nil) }
    @objc func sleeping() { server.disconnect("La Mac entró en reposo. Conecta de nuevo al volver.") }
    func startAudio() {
        guard !streaming else { return }
        do {
            try capture.start(delay:delay,trim:trim,local:local)
            let config:[String:Any]=["rate":Int(capture.rate),"channels":2,"format":"float32le","delayMs":Int(delay*1000),"version":1,"volume":volume.doubleValue]
            server.send(1,try JSONSerialization.data(withJSONObject:config)); streaming=true; lastHeartbeat=clockNS()
            stateLabel.stringValue="●  Transmitiendo"; deviceLabel.stringValue="Mac +\nGalaxy S25 Ultra"; mainButton.title="Detener transmisión"; volume.isEnabled=true
            qualityLabel.stringValue="PCM sin compresión · \(Int(delay*1000)) ms de reserva"
        } catch { server.disconnect(error.localizedDescription); stopAudio(message:error.localizedDescription) }
    }
    func stopAudio(message:String) {
        capture.stop(); streaming=false; stateLabel.stringValue="●  Listo para conectar"; mainButton.title="Conectar celular"; volume.isEnabled=false; deviceLabel.stringValue="Mac +\nTu Android"; qualityLabel.stringValue="PCM sin compresión · Perfil estable"
        detailLabel.stringValue=message
        if !message.isEmpty && message != "Listo para conectar" { stateLabel.stringValue="●  Conexión detenida"; item.button?.toolTip="Unísono: "+message }
    }
    @objc func openSettings() {
        pop.performClose(nil)
        if settings==nil {
            let w=NSWindow(contentRect:NSRect(x:0,y:0,width:460,height:710),styleMask:[.titled,.closable],backing:.buffered,defer:false); w.title="Unísono · Configuración"; w.isReleasedWhenClosed=false; w.center()
            let v=Surface(frame:NSRect(x:0,y:0,width:460,height:710)); w.contentView=v
            func put(_ s:NSView,_ y:CGFloat,_ h:CGFloat) { s.frame=NSRect(x:24,y:y,width:412,height:h); v.addSubview(s) }
            put(label("Conecta tu Android",23,.semibold),22,33)
            put(label("En la misma red Wi-Fi, escanea el QR con la cámara del teléfono o pega el enlace en Unísono para Android.",13,.regular,muted),63,45)
            linkField=NSTextField(); linkField.isEditable=false; linkField.isSelectable=true; linkField.font = .monospacedSystemFont(ofSize:11,weight:.regular); put(linkField,119,32)
            let copy=NSButton(title:"Copiar enlace de conexión",target:self,action:#selector(copyLink)); copy.bezelStyle = .rounded; put(copy,158,33)
            qrView=NSImageView(frame:NSRect(x:160,y:201,width:140,height:140)); qrView.imageScaling = .scaleProportionallyUpOrDown; v.addSubview(qrView)
            put(label("Reserva de audio",14,.medium),364,24)
            delayPicker=NSPopUpButton(); delayPicker.addItems(withTitles:["120 ms · Menor retraso","250 ms · Estable","500 ms · Mayor estabilidad"]); delayPicker.selectItem(at:delay<0.2 ? 0 : delay<0.4 ? 1 : 2); delayPicker.target=self; delayPicker.action=#selector(saveSettings); put(delayPicker,391,30)
            localSwitch=NSButton(checkboxWithTitle:"Escuchar también en la Mac",target:self,action:#selector(saveSettings)); localSwitch.state=local ? .on : .off; put(localSwitch,439,25)
            put(label("Ajuste de la Mac (ms, −100 a +100)",14,.medium),479,24)
            trimField=NSTextField(string:String(Int(trim*1000))); trimField.target=self; trimField.action=#selector(saveSettings); put(trimField,509,28)
            let save=NSButton(title:"Aplicar y reconectar",target:self,action:#selector(saveSettings)); save.bezelStyle = .rounded; put(save,550,31)
            detailLabel.frame=NSRect(x:24,y:596,width:412,height:62); v.addSubview(detailLabel)
            let rotate=NSButton(title:"Revocar clave y crear otra",target:self,action:#selector(rotateKey)); rotate.bezelStyle = .rounded; put(rotate,661,29)
            settings=w
        }
        linkField.stringValue="unisono://\(ips().first ?? "127.0.0.1"):45871#\(secret.hex)"
        if let f=CIFilter(name:"CIQRCodeGenerator") { f.setValue(Data(linkField.stringValue.utf8),forKey:"inputMessage"); f.setValue("M",forKey:"inputCorrectionLevel"); if let image=f.outputImage?.transformed(by:CGAffineTransform(scaleX:8,y:8)),let cg=CIContext().createCGImage(image,from:image.extent) { qrView.image=NSImage(cgImage:cg,size:NSSize(width:image.extent.width,height:image.extent.height)) } }
        settings?.makeKeyAndOrderFront(nil); NSApp.activate(ignoringOtherApps:true)
    }
    @objc func copyLink() { NSPasteboard.general.clearContents(); NSPasteboard.general.setString(linkField.stringValue,forType:.string); detailLabel.stringValue="Enlace copiado. En Android, pégalo en Conectar. Otras IP de esta Mac: "+ips().joined(separator:", ") }
    @objc func saveSettings() {
        let ms=Double(trimField.stringValue.replacingOccurrences(of:",",with:".")) ?? 0
        UserDefaults.standard.set([0.12,0.25,0.5][delayPicker.indexOfSelectedItem],forKey:"delay"); UserDefaults.standard.set(max(-100,min(100,ms))/1000,forKey:"trim"); UserDefaults.standard.set(localSwitch.state == .on,forKey:"local"); server.endSession("Ajustes guardados. Vuelve a conectar desde Android.")
    }
    @objc func rotateKey() { server.disconnect(""); secret=randomBytes(16); UserDefaults.standard.set(secret.hex,forKey:"pairKey"); server.secret=secret; openSettings(); detailLabel.stringValue="Clave anterior revocada. Copia el nuevo enlace en el celular." }
    func preview() {
        stateLabel.stringValue="●  Transmitiendo"; deviceLabel.stringValue="Mac +\nGalaxy S25 Ultra"; mainButton.title="Detener transmisión"; volume.isEnabled=true
        let w=NSWindow(contentRect:NSRect(x:0,y:0,width:360,height:558),styleMask:[.borderless],backing:.buffered,defer:false); w.contentView=pop.contentViewController!.view; w.center(); w.makeKeyAndOrderFront(nil); settings=w
        snapshot(w)
    }
    func snapshot(_ w:NSWindow) {
        DispatchQueue.main.asyncAfter(deadline:.now()+0.4) {
            if let v=w.contentView,let rep=v.bitmapImageRepForCachingDisplay(in:v.bounds) { v.cacheDisplay(in:v.bounds,to:rep); if let data=rep.representation(using:.png,properties:[:]) { try? data.write(to:URL(fileURLWithPath:CommandLine.arguments.last!)) } }; NSApp.terminate(nil)
        }
    }
    func applicationWillTerminate(_ n:Notification) { capture.stop(); server?.stop() }
}
let app=NSApplication.shared
let delegate=App(); app.delegate=delegate; app.run()
