import AppKit
import Foundation
import CryptoKit
import Darwin
import CoreImage

let mint=NSColor(srgbRed:0.49,green:0.81,blue:0.62,alpha:1)
let ink=NSColor(white:0.94,alpha:1)
let buttonInk=NSColor(white:0.05,alpha:1)
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
    override init(frame:NSRect) { super.init(frame:frame); appearance=NSAppearance(named:.darkAqua); wantsLayer=true; layer?.backgroundColor=NSColor(srgbRed:0.10,green:0.11,blue:0.11,alpha:1).cgColor }
    required init?(coder:NSCoder) { fatalError() }
}
func label(_ text:String,_ size:CGFloat,_ weight:NSFont.Weight = .regular,_ color:NSColor = ink) -> NSTextField {
    let l=NSTextField(wrappingLabelWithString:text); l.font = .systemFont(ofSize:size,weight:weight); l.textColor=color; l.isSelectable=false; return l
}
// Attributed titles keep borderless controls legible even in a light menu-bar host.
func tintTitle(_ button:NSButton,_ color:NSColor = ink) {
    let paragraph=NSMutableParagraphStyle(); paragraph.alignment=button.alignment
    let title=NSAttributedString(string:button.title,attributes:[.foregroundColor:color,.font:button.font ?? NSFont.systemFont(ofSize:13),.paragraphStyle:paragraph])
    button.attributedTitle=title; button.attributedAlternateTitle=title; button.contentTintColor=color
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
    var encoder:AACEncoder?
    var server:LinkServer!
    var stateLabel:NSTextField!, deviceLabel:NSTextField!, qualityLabel:NSTextField!, detailLabel:NSTextField!, mainButton:NSButton!, volume:NSSlider!
    var linkField:NSTextField!, delayField:NSTextField!, trimField:NSTextField!, localSwitch:NSButton!, settingsFeedback:NSTextField!
    var qrView:NSImageView!
    var streaming=false, lastHeartbeat=clockNS(), watchdog:Timer?
    var secret=Data()
    var reserveMilliseconds:Int { LatencySettings.savedMilliseconds(seconds:UserDefaults.standard.double(forKey:"delay")) }
    var trim:Double { max(-0.1,min(0.1,UserDefaults.standard.double(forKey:"trim"))) }
    var local:Bool { UserDefaults.standard.object(forKey:"local")==nil || UserDefaults.standard.bool(forKey:"local") }
    func applicationDidFinishLaunching(_ notification:Notification) {
        NSApp.setActivationPolicy(.accessory); NSApp.appearance=NSAppearance(named:CommandLine.arguments.contains("--light-host") ? .aqua : .darkAqua)
        if let saved=UserDefaults.standard.string(forKey:"pairKey"),let d=Data(hex:saved) { secret=d } else { secret=randomBytes(16); UserDefaults.standard.set(secret.hex,forKey:"pairKey") }
        item=NSStatusBar.system.statusItem(withLength:NSStatusItem.squareLength)
        let menuIcon=NSImage(named:"MenuBarTemplate") ?? NSImage(systemSymbolName:"waveform",accessibilityDescription:"Unísono")
        menuIcon?.size=NSSize(width:20,height:18); menuIcon?.isTemplate=true
        item.button?.image=menuIcon; item.button?.setAccessibilityLabel("Unísono"); item.button?.target=self; item.button?.action=#selector(toggle)
        item.button?.toolTip="Unísono — Audio compartido"
        pop.appearance=NSAppearance(named:.darkAqua); pop.behavior = .transient; pop.contentSize=NSSize(width:360,height:558)
        let vc=NSViewController(); vc.view=makePanel(); pop.contentViewController=vc
        server=LinkServer(secret:secret)
        server.onReady={ [weak self] in self?.startAudio() }
        server.onDisconnect={ [weak self] why in self?.stopAudio(message:why.isEmpty ? "Listo para conectar" : why) }
        server.onCommand={ [weak self] type,payload in
            guard let self=self else { return }
            if type==7 { self.server.disconnect("") }
            if type==8 { self.lastHeartbeat=clockNS(); if let obj=(try? JSONSerialization.jsonObject(with:payload)) as? [String:Any] {
                let error=(obj["syncMs"] as? Double).map { String(format:"Desfase estimado: %+.0f ms",$0) } ?? "Midiendo sincronización…"
                let buffer=(obj["bufferMs"] as? Int).map { " · Búfer de salida \($0) ms" } ?? ""
                let gaps=(obj["underruns"] as? Int).map { " · Cortes: \($0)" } ?? ""
                self.detailLabel.stringValue=error+buffer+gaps
                if let name=obj["device"] as? String { self.deviceLabel.stringValue="Mac +\n"+String(name.prefix(28)) }
            } }
        }
        capture.onPacket={ [weak self] p in
            guard let self=self else { return }
            do {
                if let encoder=self.encoder { for packet in try encoder.encode(p) { if !self.server.ready { break }; self.server.send(10,packet) } }
                else { self.server.send(2,p) }
            } catch { self.server.disconnect("No se pudo codificar el audio. Prueba PCM sin pérdida.") }
        }
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
        func line(_ y:CGFloat) { let b=NSBox(); b.boxType = .custom; b.borderWidth=0; b.fillColor=NSColor(white:0.20,alpha:1); put(b,0,y,360,1) }
        put(label("Unísono",24,.semibold),28,23,245,35)
        let gear=NSButton(image:NSImage(systemSymbolName:"gearshape",accessibilityDescription:"Configuración")!,target:self,action:#selector(openSettings)); gear.isBordered=false; gear.contentTintColor=ink; put(gear,305,24,28,28)
        line(82)
        stateLabel=label("●  Listo para conectar",15,.medium,mint); put(stateLabel,28,104,304,24)
        deviceLabel=label("Mac +\nTu Android",28,.semibold); put(deviceLabel,28,144,305,78)
        put(label("Audio en ambos dispositivos",16,.regular,NSColor(white:0.76,alpha:1)),28,228,310,25)
        qualityLabel=label("Calidad adaptable · Lista para conectar",13,.regular,muted); put(qualityLabel,28,264,305,24)
        line(300)
        let speaker=NSImageView(image:NSImage(systemSymbolName:"speaker.wave.2.fill",accessibilityDescription:nil)!); speaker.contentTintColor=ink; put(speaker,28,320,23,23)
        put(label("Volumen del celular",17),60,319,266,27)
        volume=NSSlider(); volume.cell=MintSliderCell(); volume.minValue=0; volume.maxValue=1; volume.doubleValue=0.6; volume.target=self; volume.action=#selector(changeVolume); volume.isContinuous=true; volume.isEnabled=false; put(volume,27,355,307,27)
        mainButton=NSButton(title:"Conectar celular",target:self,action:#selector(primary)); mainButton.bezelStyle = .rounded; mainButton.isBordered=false; mainButton.wantsLayer=true; mainButton.layer?.backgroundColor=mint.cgColor; mainButton.layer?.cornerRadius=9; mainButton.font = .systemFont(ofSize:18,weight:.medium); tintTitle(mainButton,buttonInk); put(mainButton,26,398,308,48)
        line(466)
        let change=NSButton(title:"Cambiar dispositivo                         ›",target:self,action:#selector(openSettings)); change.isBordered=false; change.alignment = .left; change.font = .systemFont(ofSize:15); tintTitle(change); put(change,27,480,307,26)
        line(517)
        let quit=NSButton(title:"Salir de Unísono",target:self,action:#selector(quitApp)); quit.isBordered=false; quit.font = .systemFont(ofSize:14); quit.contentTintColor=muted; quit.alignment = .left; tintTitle(quit,muted); put(quit,27,527,220,25)
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
            let request=(try? JSONSerialization.jsonObject(with:server.captureRequest)) as? [String:Any] ?? [:]
            let wantsAAC=(request["codec"] as? String)=="aac-lc"
            let reserveMs=LatencySettings.negotiate(macMilliseconds:reserveMilliseconds,receiverMilliseconds:request["reserveMs"] as? Int)
            let reserve=Double(reserveMs)/1000
            try capture.start(delay:reserve,trim:trim,local:local)
            encoder=wantsAAC ? try? AACEncoder(sourceRate:capture.rate,bitRate:(request["bitrate"] as? Int)==160000 ? 160000 : 256000) : nil
            var config:[String:Any]=["rate":encoder?.rate ?? Int(capture.rate),"channels":2,"format":encoder==nil ? "float32le" : "aac-lc","delayMs":reserveMs,"version":1,"volume":volume.doubleValue]
            if let encoder=encoder { config["bitrate"]=encoder.bitRate; config["primingFrames"]=encoder.primingFrames }
            let quality=encoder.map { "AAC · \($0.bitRate/1000) kbps" } ?? "PCM sin compresión"
            server.send(1,try JSONSerialization.data(withJSONObject:config)); streaming=true; lastHeartbeat=clockNS()
            stateLabel.stringValue="●  Transmitiendo"; deviceLabel.stringValue="Mac +\nGalaxy S25 Ultra"; mainButton.title="Detener transmisión"; tintTitle(mainButton,buttonInk); volume.isEnabled=true
            qualityLabel.stringValue="\(quality) · \(reserveMs) ms de reserva"
        } catch { server.disconnect(error.localizedDescription); stopAudio(message:error.localizedDescription) }
    }
    func stopAudio(message:String) {
        capture.stop(); encoder=nil; streaming=false; stateLabel.stringValue="●  Listo para conectar"; mainButton.title="Conectar celular"; tintTitle(mainButton,buttonInk); volume.isEnabled=false; deviceLabel.stringValue="Mac +\nTu Android"; qualityLabel.stringValue="Calidad adaptable · Lista para conectar"
        detailLabel.stringValue=message
        if !message.isEmpty && message != "Listo para conectar" { stateLabel.stringValue="●  Conexión detenida"; item.button?.toolTip="Unísono: "+message }
    }
    @objc func openSettings() {
        pop.performClose(nil)
        if settings==nil {
            let w=NSWindow(contentRect:NSRect(x:0,y:0,width:460,height:790),styleMask:[.titled,.closable],backing:.buffered,defer:false); w.appearance=NSAppearance(named:.darkAqua); w.title="Unísono · Configuración"; w.isReleasedWhenClosed=false; w.center()
            let v=Surface(frame:NSRect(x:0,y:0,width:460,height:790)); w.contentView=v
            func put(_ s:NSView,_ y:CGFloat,_ h:CGFloat) { s.frame=NSRect(x:24,y:y,width:412,height:h); v.addSubview(s) }
            put(label("Conecta tu Android",23,.semibold),22,33)
            put(label("En la misma red Wi-Fi, escanea el QR con la cámara del teléfono o pega el enlace en Unísono para Android.",13,.regular,muted),63,45)
            linkField=NSTextField(); linkField.textColor=ink; linkField.backgroundColor=NSColor(white:0.16,alpha:1); linkField.isEditable=false; linkField.isSelectable=true; linkField.font = .monospacedSystemFont(ofSize:11,weight:.regular); put(linkField,119,32)
            let copy=NSButton(title:"Copiar enlace de conexión",target:self,action:#selector(copyLink)); copy.bezelStyle = .rounded; tintTitle(copy); put(copy,158,33)
            qrView=NSImageView(frame:NSRect(x:160,y:201,width:140,height:140)); qrView.imageScaling = .scaleProportionallyUpOrDown; v.addSubview(qrView)
            put(label("Reserva de audio · 250–1000 ms",14,.medium),353,24)
            delayField=NSTextField(string:String(reserveMilliseconds)); delayField.textColor=ink; delayField.backgroundColor=NSColor(white:0.16,alpha:1); delayField.target=self; delayField.action=#selector(saveSettings); delayField.setAccessibilityLabel("Reserva de audio en milisegundos"); put(delayField,382,28)
            put(label("AAC equilibrado y PCM: desde 250 ms. Más estable: mínimo 750 ms. Puede aumentar tras cortes.",12,.regular,muted),420,36)
            localSwitch=NSButton(checkboxWithTitle:"Escuchar también en la Mac",target:self,action:#selector(saveSettings)); tintTitle(localSwitch); localSwitch.state=local ? .on : .off; put(localSwitch,470,25)
            put(label("Sincronización de la Mac · −100 a +100 ms",14,.medium),509,24)
            trimField=NSTextField(string:String(format:"%g",trim*1000)); trimField.textColor=ink; trimField.backgroundColor=NSColor(white:0.16,alpha:1); trimField.target=self; trimField.action=#selector(saveSettings); trimField.setAccessibilityLabel("Ajuste de sincronización de la Mac en milisegundos"); put(trimField,538,28)
            put(label("Solo adelanta o retrasa la Mac. No cambia la reserva ni el búfer de salida del teléfono.",12,.regular,muted),577,34)
            let save=NSButton(title:"Aplicar y reconectar",target:self,action:#selector(saveSettings)); save.bezelStyle = .rounded; tintTitle(save); put(save,622,31)
            settingsFeedback=label("",12,.regular,mint); put(settingsFeedback,661,34)
            detailLabel.frame=NSRect(x:24,y:701,width:412,height:31); v.addSubview(detailLabel)
            let rotate=NSButton(title:"Revocar clave y crear otra",target:self,action:#selector(rotateKey)); rotate.bezelStyle = .rounded; tintTitle(rotate); put(rotate,744,29)
            settings=w
        }
        linkField.stringValue="unisono://\(ips().first ?? "127.0.0.1"):45871#\(secret.hex)"
        if let f=CIFilter(name:"CIQRCodeGenerator") { f.setValue(Data(linkField.stringValue.utf8),forKey:"inputMessage"); f.setValue("M",forKey:"inputCorrectionLevel"); if let image=f.outputImage?.transformed(by:CGAffineTransform(scaleX:8,y:8)),let cg=CIContext().createCGImage(image,from:image.extent) { qrView.image=NSImage(cgImage:cg,size:NSSize(width:image.extent.width,height:image.extent.height)) } }
        settings?.makeKeyAndOrderFront(nil); NSApp.activate(ignoringOtherApps:true)
    }
    @objc func copyLink() { NSPasteboard.general.clearContents(); NSPasteboard.general.setString(linkField.stringValue,forType:.string); detailLabel.stringValue="Enlace copiado. En Android, pégalo en Conectar. Otras IP de esta Mac: "+ips().joined(separator:", ") }
    @objc func saveSettings() {
        guard let reserve=LatencySettings.parseReserve(delayField.stringValue) else { settingsFeedback.textColor = .systemOrange; settingsFeedback.stringValue="Reserva: escribe un número entero entre 250 y 1000 ms."; return }
        guard let ms=LatencySettings.parseTrim(trimField.stringValue) else { settingsFeedback.textColor = .systemOrange; settingsFeedback.stringValue="Sincronización: usa −100 a +100 ms. Para 250 ms, cambia la reserva de audio."; return }
        UserDefaults.standard.set(Double(reserve)/1000,forKey:"delay"); UserDefaults.standard.set(ms/1000,forKey:"trim"); UserDefaults.standard.set(localSwitch.state == .on,forKey:"local")
        delayField.stringValue=String(reserve); trimField.stringValue=String(format:"%g",ms)
        settingsFeedback.textColor=mint; settingsFeedback.stringValue="Reserva guardada: \(reserve) ms. Vuelve a conectar desde Android."
        server.endSession("Ajustes guardados. Vuelve a conectar desde Android.")
    }
    @objc func rotateKey() { server.disconnect(""); secret=randomBytes(16); UserDefaults.standard.set(secret.hex,forKey:"pairKey"); server.secret=secret; openSettings(); detailLabel.stringValue="Clave anterior revocada. Copia el nuevo enlace en el celular." }
    func preview() {
        qualityLabel.stringValue="AAC · 256 kbps · 500 ms de reserva"
        stateLabel.stringValue="●  Transmitiendo"; deviceLabel.stringValue="Mac +\nGalaxy S25 Ultra"; mainButton.title="Detener transmisión"; tintTitle(mainButton,buttonInk); volume.isEnabled=true
        if CommandLine.arguments.contains("--popover") {
            // Exercise a real popover inside a light/dark host, independent of menu-bar overflow.
            let host=NSWindow(contentRect:NSRect(x:300,y:300,width:100,height:40),styleMask:[.borderless],backing:.buffered,defer:false)
            host.appearance=NSApp.appearance
            let anchor=NSButton(frame:NSRect(x:0,y:0,width:100,height:40)); host.contentView?.addSubview(anchor)
            host.makeKeyAndOrderFront(nil); settings=host; NSApp.activate(ignoringOtherApps:true)
            pop.animates=false; pop.behavior = .applicationDefined
            pop.show(relativeTo:anchor.bounds,of:anchor,preferredEdge:.minY)
            DispatchQueue.main.asyncAfter(deadline:.now()+0.3) {
                guard let window=self.pop.contentViewController?.view.window else { fputs("Popover did not open\n",stderr); exit(1) }
                self.snapshot(window)
            }
            return
        }
        let w=NSWindow(contentRect:NSRect(x:0,y:0,width:360,height:558),styleMask:[.borderless],backing:.buffered,defer:false); w.appearance=NSApp.appearance; w.contentView=pop.contentViewController!.view; w.center(); w.makeKeyAndOrderFront(nil); settings=w
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
