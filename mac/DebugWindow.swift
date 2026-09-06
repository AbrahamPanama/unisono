import AppKit
import UniformTypeIdentifiers

private let debugBlue=NSColor(srgbRed:0.48,green:0.72,blue:1,alpha:1)
private let debugAmber=NSColor(srgbRed:1,green:0.76,blue:0.40,alpha:1)
private let debugViolet=NSColor(srgbRed:0.77,green:0.65,blue:1,alpha:1)
private struct DebugLine { let key:String; let name:String; let color:NSColor; var scale:Double=1; var offset:Double=0 }
private final class DebugChart:NSView {
    override var isFlipped:Bool { true }
    let title:String, unit:String
    let lines:[DebugLine]
    var samples=[DebugSample]() { didSet { needsDisplay=true } }
    init(title:String,unit:String,lines:[DebugLine],frame:NSRect) {
        self.title=title; self.unit=unit; self.lines=lines; super.init(frame:frame)
        wantsLayer=true; layer?.backgroundColor=NSColor(white:0.13,alpha:1).cgColor; layer?.cornerRadius=12
        setAccessibilityElement(true); setAccessibilityRole(.image); setAccessibilityLabel(title)
    }
    required init?(coder:NSCoder) { fatalError() }
    func text(_ text:String,_ point:NSPoint,_ color:NSColor=muted,_ size:CGFloat=10) { (text as NSString).draw(at:point,withAttributes:[.font:NSFont.systemFont(ofSize:size),.foregroundColor:color]) }
    override func draw(_ dirtyRect:NSRect) {
        super.draw(dirtyRect)
        text(title,NSPoint(x:16,y:12),ink,14)
        let rect=NSRect(x:48,y:65,width:bounds.width-64,height:bounds.height-91)
        var values=[Double]()
        for sample in samples { for series in lines { if let v=sample.value(series.key) { values.append(v*series.scale+series.offset) } } }
        let low=values.min() ?? 0,high=values.max() ?? 1
        let span=max(high-low,unit=="dBFS" ? 6 : 2)
        let minValue=low-span*0.12,maxValue=high+span*0.12
        for i in 0...3 {
            let y=rect.minY+rect.height*CGFloat(i)/3
            NSColor(white:0.23,alpha:1).setStroke(); let p=NSBezierPath(); p.move(to:NSPoint(x:rect.minX,y:y)); p.line(to:NSPoint(x:rect.maxX,y:y)); p.lineWidth=0.5; p.stroke()
            text(String(format:"%.0f",maxValue-(maxValue-minValue)*Double(i)/3),NSPoint(x:5,y:y-6))
        }
        var legendX:CGFloat=16
        for series in lines {
            let value=samples.last?.value(series.key).map { String(format:"%.1f",$0*series.scale+series.offset) } ?? "—"
            let legend="\(series.name) \(value)"
            text(legend,NSPoint(x:legendX,y:36),series.color,10); legendX+=CGFloat(legend.count)*5.2+13
        }
        guard let first=samples.first?.time,let last=samples.last?.time,last>first else { text("Esperando mediciones",NSPoint(x:rect.midX-60,y:rect.midY)); return }
        let duration=last.timeIntervalSince(first)
        for series in lines {
            let path=NSBezierPath(); path.lineWidth=1.6; series.color.setStroke(); var previous:Date?
            for sample in samples {
                guard let value=sample.value(series.key) else { previous=nil; continue }
                let x=rect.minX+rect.width*CGFloat(sample.time.timeIntervalSince(first)/duration)
                let y=rect.maxY-rect.height*CGFloat((value*series.scale+series.offset-minValue)/(maxValue-minValue))
                if previous.map({ sample.time.timeIntervalSince($0)<2.5 }) == true { path.line(to:NSPoint(x:x,y:y)) } else { path.move(to:NSPoint(x:x,y:y)) }
                previous=sample.time
            }
            path.stroke()
        }
        text("−\(Int(duration)) s",NSPoint(x:rect.minX,y:rect.maxY+8)); text("Ahora · \(unit)",NSPoint(x:rect.maxX-80,y:rect.maxY+8))
    }
}

final class DebugWindow:NSObject,NSTextFieldDelegate {
    let store:DebugStats
    let window:NSWindow
    var apply:((DebugSettings,Double)throws->Void)?
    var saveTrim:((Double)throws->Void)?
    var currentTrim:()->Double = { 0 }
    var paused=false,dirty=false,seenRevision = -1
    private var pendingProfile:DebugSettings?, pendingSince:Date?, pendingTrim:Double?
    private var charts=[DebugChart]()
    private var summary=[NSTextField]()
    private var latestSamples=[DebugSample]()
    private let mode=NSPopUpButton(),reserve=NSTextField(),buffer=NSTextField(),prefill=NSTextField(),trim=NSTextField()
    private let correction=NSButton(checkboxWithTitle:"Corregir deriva del reloj",target:nil,action:nil)
    private let retry=NSButton(checkboxWithTitle:"Reconectar tras un corte",target:nil,action:nil)
    private let applyButton=NSButton(title:"Aplicar y reconectar",target:nil,action:nil)
    private let useCurrentButton=NSButton(title:"Usar valores actuales",target:nil,action:nil)
    private let pauseButton=NSButton(title:"Pausar gráficos",target:nil,action:nil)
    private let feedback=label("",12,.regular,mint)
    private let connection=label("Sin conexión",12,.medium,muted)
    private let measurements=label("",12,.regular,ink)
    private let eventText=NSTextView(frame:NSRect(x:0,y:0,width:910,height:195))

    init(store:DebugStats) {
        self.store=store
        window=NSWindow(contentRect:NSRect(x:0,y:0,width:1000,height:790),styleMask:[.titled,.closable,.miniaturizable,.resizable],backing:.buffered,defer:false)
        super.init()
        window.title="Unísono · Depuración de latencia"; window.appearance=NSAppearance(named:.darkAqua); window.isReleasedWhenClosed=false; window.minSize=NSSize(width:1000,height:650); window.center()
        let scroll=NSScrollView(frame:NSRect(x:0,y:0,width:1000,height:790)); scroll.hasVerticalScroller=true; scroll.autoresizingMask=[.width,.height]; scroll.drawsBackground=false
        let content=Surface(frame:NSRect(x:0,y:0,width:980,height:2170)); content.autoresizingMask=[.width]; scroll.documentView=content; window.contentView=scroll
        func put(_ view:NSView,_ x:CGFloat,_ y:CGFloat,_ w:CGFloat,_ h:CGFloat) { view.frame=NSRect(x:x,y:y,width:w,height:h); content.addSubview(view) }
        put(label("Depuración de latencia",28,.semibold),28,24,520,38)
        put(connection,28,68,540,22)
        put(label("Mide un cambio a la vez. Reserva, búfer y desfase son valores distintos.",13,.regular,muted),28,95,850,24)
        let export=NSButton(title:"Exportar JSON…",target:self,action:#selector(exportJSON)); export.bezelStyle = .rounded; tintTitle(export); put(export,798,28,156,30)
        pauseButton.target=self; pauseButton.action=#selector(togglePause); pauseButton.bezelStyle = .rounded; tintTitle(pauseButton); put(pauseButton,625,28,163,30)
        let clear=NSButton(title:"Borrar historial",target:self,action:#selector(clearHistory)); clear.bezelStyle = .rounded; tintTitle(clear); put(clear,798,64,156,28)
        put(label("CONTROL DE REPRODUCCIÓN",11,.semibold,muted),28,135,850,20)
        mode.addItems(withTitles:["Automático", "Manual · valores fijos"]); mode.target=self; mode.action=#selector(changed); mode.contentTintColor=ink; put(mode,28,163,225,30)
        let fields:[(String,NSTextField,CGFloat,CGFloat,String)] = [
            ("Reserva · 100–2000 ms",reserve,273,192,"Reserva manual en milisegundos"),
            ("Búfer · 20–500 ms",buffer,482,165,"Búfer solicitado en milisegundos"),
            ("Precarga · 10–200 ms",prefill,664,166,"Precarga en milisegundos")]
        for (title,field,x,w,accessibility) in fields {
            put(label(title,12,.medium),x,140,w,22); field.textColor=ink; field.backgroundColor=NSColor(white:0.17,alpha:1); field.font = .monospacedDigitSystemFont(ofSize:14,weight:.medium); field.delegate=self; field.setAccessibilityLabel(accessibility); put(field,x,166,w,25)
        }
        correction.target=self; correction.action=#selector(changed); tintTitle(correction); put(correction,28,207,225,26)
        retry.target=self; retry.action=#selector(changed); tintTitle(retry); put(retry,273,207,240,26)
        put(label("Ajuste de la Mac · −500 a +500 ms",12,.medium),535,207,270,24)
        trim.textColor=ink; trim.backgroundColor=NSColor(white:0.17,alpha:1); trim.delegate=self; trim.setAccessibilityLabel("Ajuste de la Mac en milisegundos"); put(trim,822,206,108,26)
        put(label("Manual fija los valores y desactiva los aumentos de reserva y búfer. Android puede imponer un búfer real mayor al solicitado. Los cambios reinician la transmisión. Ajuste positivo: la Mac suena más tarde.",12,.regular,muted),28,243,900,42)
        applyButton.target=self; applyButton.action=#selector(applySettings); applyButton.bezelStyle = .rounded; tintTitle(applyButton); put(applyButton,28,298,220,32)
        useCurrentButton.target=self; useCurrentButton.action=#selector(useCurrentValues); useCurrentButton.bezelStyle = .rounded; tintTitle(useCurrentButton); put(useCurrentButton,260,298,220,32)
        put(feedback,28,335,916,26)
        let saveOffset=NSButton(title:"Guardar ajuste Mac",target:self,action:#selector(saveMacTrim)); saveOffset.bezelStyle = .rounded; tintTitle(saveOffset); saveOffset.isEnabled = !store.fixture; put(saveOffset,778,298,166,32)
        let names=["Reserva objetivo", "Latencia estimada¹", "Búfer real / pedido", "Desfase relativo a la Mac²", "RTT de red", "Cortes acumulados", "Cola Android", "Salida pendiente"]
        for i in 0..<8 {
            let x:CGFloat=28+CGFloat(i%4)*232,y:CGFloat=361+CGFloat(i/4)*76
            put(label(names[i],12,.regular,muted),x,y,218,19)
            let value=label("—",23,.semibold); put(value,x,y+24,218,35); summary.append(value)
        }
        put(label("¹ Estimación de software desde la captura hasta la salida de Android; no mide el sonido físico ni el retardo del DAC. ² Desfase de Android respecto a la Mac tras su ajuste local; ambos son estimaciones de software.",12,.regular,muted),28,518,916,41)
        let definitions:[(String,String,[DebugLine])] = [
            ("Reserva y latencia estimada","ms",[DebugLine(key:"phone.reserveMs",name:"Objetivo",color:mint),DebugLine(key:"phone.estimatedLatencyMs",name:"Estimada",color:debugBlue)]),
            ("Desfase frente al objetivo compartido","ms",[DebugLine(key:"phone.syncMs",name:"Objetivo",color:debugAmber),DebugLine(key:"phone.macRelativeMs",name:"Frente a Mac",color:mint)]),
            ("Audio esperando reproducirse","ms",[DebugLine(key:"phone.queueMs",name:"Cola",color:mint),DebugLine(key:"phone.queuedOutputMs",name:"Salida",color:debugBlue),DebugLine(key:"phone.bufferMs",name:"Capacidad",color:debugViolet)]),
            ("Red · RTT, variación e intervalo máximo","ms",[DebugLine(key:"phone.rttMs",name:"RTT",color:mint),DebugLine(key:"phone.jitterMs",name:"Variación",color:debugBlue),DebugLine(key:"phone.packetGapMaxMs",name:"Intervalo máx. 1 s",color:debugAmber)]),
            ("Trabajo de audio · máximos de 1 s","ms",[DebugLine(key:"mac.encodeMaxMs",name:"Codificar",color:mint),DebugLine(key:"phone.decodeMaxMs",name:"Decodificar",color:debugBlue),DebugLine(key:"phone.writeMaxMs",name:"Escribir",color:debugAmber)]),
            ("Tráfico de audio y protocolo","kbps",[DebugLine(key:"mac.sendKbps",name:"Mac envía",color:mint),DebugLine(key:"phone.kbps",name:"Android recibe",color:debugBlue)]),
            ("Corrección de velocidad","ppm",[DebugLine(key:"phone.speed",name:"Ajuste",color:mint,scale:1_000_000,offset:-1_000_000)]),
            ("Pico de la fuente capturada","dBFS",[DebugLine(key:"mac.sourcePeakDbfs",name:"Pico",color:debugBlue)])]
        for (i,definition) in definitions.enumerated() {
            let chart=DebugChart(title:definition.0,unit:definition.1,lines:definition.2,frame:NSRect(x:28+CGFloat(i%2)*465,y:576+CGFloat(i/2)*190,width:447,height:174)); content.addSubview(chart); charts.append(chart)
        }
        put(label("TODAS LAS MEDICIONES",11,.semibold,muted),28,1345,850,22)
        measurements.font = .monospacedSystemFont(ofSize:11,weight:.regular); measurements.isSelectable=true; put(measurements,28,1376,920,440)
        put(label("Eventos · últimos 200",18,.semibold),28,1855,800,28)
        eventText.isEditable=false; eventText.isSelectable=true; eventText.textColor=muted; eventText.backgroundColor=NSColor(white:0.13,alpha:1); eventText.font = .systemFont(ofSize:12); eventText.isVerticallyResizable=true; eventText.textContainer?.widthTracksTextView=true; eventText.textContainer?.containerSize=NSSize(width:910,height:CGFloat.greatestFiniteMagnitude)
        let eventsScroll=NSScrollView(); eventsScroll.hasVerticalScroller=true; eventsScroll.documentView=eventText; put(eventsScroll,28,1896,920,195)
        put(label("Historial: 10 minutos a 1 Hz. Los huecos indican datos ausentes. Pausar congela la vista; las mediciones continúan. El JSON omite claves, direcciones de red, nombres de dispositivos; los mensajes de eventos se anonimizan.",12,.regular,muted),28,2120,920,40)
        loadControls(force:true); refresh()
    }
    func show() { window.makeKeyAndOrderFront(nil); NSApp.activate(ignoringOtherApps:true); refresh() }
    func loadControls(force:Bool=false) {
        guard force || (!dirty && seenRevision != store.profileRevision) else { return }
        let profile=store.profile ?? .defaults; mode.selectItem(at:profile.manual ? 1 : 0); reserve.stringValue=String(profile.reserveMs); buffer.stringValue=String(profile.bufferMs); prefill.stringValue=String(profile.prefillMs)
        correction.state=profile.clockCorrection ? .on : .off; retry.state=profile.retry ? .on : .off; trim.stringValue=String(format:"%g",currentTrim())
        seenRevision=store.profileRevision; dirty=false; updateEnabled()
    }
    private func updateEnabled() {
        let editable=store.connected && store.profile != nil && !store.fixture
        applyButton.isEnabled=editable; useCurrentButton.isEnabled=editable
        mode.isEnabled=editable; trim.isEnabled = !store.fixture
        let manual=mode.indexOfSelectedItem==1
        for field in [reserve,buffer,prefill] { field.isEnabled=editable && manual }
        correction.isEnabled=editable && manual; retry.isEnabled=editable && manual
    }
    @objc func changed() { dirty=true; updateEnabled(); feedback.textColor=mint; feedback.stringValue="Cambios pendientes. Se aplican al reconectar." }
    func controlTextDidChange(_ obj:Notification) { changed() }
    private func parsedTrim() throws -> Double {
        guard let t=Double(trim.stringValue.replacingOccurrences(of:",",with:".")),t.isFinite,(-500...500).contains(t) else { throw NSError(domain:"Debug",code:3,userInfo:[NSLocalizedDescriptionKey:"Ajuste de la Mac: escribe un valor entre −500 y +500 ms."]) }
        return t
    }
    @objc func saveMacTrim() {
        do { try saveTrim?(parsedTrim()); feedback.textColor=mint; feedback.stringValue="Ajuste local guardado. Se aplica en la próxima conexión." }
        catch { feedback.textColor=debugAmber; feedback.stringValue=error.localizedDescription }
    }
    @objc func useCurrentValues() {
        guard let r=store.phone["reserveMs"] as? NSNumber,let b=store.phone["requestedBufferMs"] as? NSNumber,let p=store.phone["requestedPrefillMs"] as? NSNumber else { feedback.textColor=debugAmber; feedback.stringValue="Espera las mediciones de reserva, búfer y precarga de Android."; return }
        do {
            let settings=try DebugSettings.parse(["manual":true,"reserveMs":r,"bufferMs":b,"prefillMs":p,"clockCorrection":store.phone["clockCorrectionEnabled"] as? Bool ?? true,"retry":store.phone["retryEnabled"] as? Bool ?? true])
            mode.selectItem(at:1); reserve.stringValue=String(settings.reserveMs); buffer.stringValue=String(settings.bufferMs); prefill.stringValue=String(settings.prefillMs); correction.state=settings.clockCorrection ? .on : .off; retry.state=settings.retry ? .on : .off
            trim.stringValue=String(format:"%g",currentTrim()); changed(); feedback.stringValue="Valores actuales copiados al borrador manual. Aplica cuando quieras probarlos."
        } catch { feedback.textColor=debugAmber; feedback.stringValue=error.localizedDescription }
    }
    @objc func applySettings() {
        do {
            let t=try parsedTrim()
            var profile:DebugSettings
            if mode.indexOfSelectedItem==1 {
                guard let r=Double(reserve.stringValue),let b=Double(buffer.stringValue),let p=Double(prefill.stringValue) else { throw DebugSettings.Invalid.configuration }
                profile=try DebugSettings.parse(["manual":true,"reserveMs":r,"bufferMs":b,"prefillMs":p,"clockCorrection":correction.state == .on,"retry":retry.state == .on])
            } else {
                profile=store.profile ?? .defaults; profile.manual=false; profile.clockCorrection=true; profile.retry=true
            }
            try apply?(profile,t); pendingProfile=profile; pendingSince=Date(); pendingTrim=t
            dirty=false; feedback.textColor=mint; feedback.stringValue="Enviado. Esperando la nueva conexión y sus valores reales…"
        } catch { feedback.textColor=debugAmber; feedback.stringValue=error.localizedDescription }
    }
    @objc func togglePause() { paused.toggle(); pauseButton.title=paused ? "Reanudar gráficos" : "Pausar gráficos"; tintTitle(pauseButton); if !paused { refresh() } }
    @objc func clearHistory() { store.clear(); latestSamples=[]; for chart in charts { chart.samples=[] }; eventText.string="Historial borrado."; refresh() }
    @objc func exportJSON() {
        let panel=NSSavePanel(); panel.nameFieldStringValue="Unisono-latency-\(Int(Date().timeIntervalSince1970)).json"; panel.allowedContentTypes=[.json]
        panel.beginSheetModal(for:window) { [weak self] response in
            guard let self=self,response == .OK,let url=panel.url else { return }
            do { try self.store.export().write(to:url,options:.atomic); self.feedback.textColor=mint; self.feedback.stringValue="Historial exportado sin identificadores privados." }
            catch { self.feedback.textColor=debugAmber; self.feedback.stringValue="No se pudo guardar: "+error.localizedDescription }
        }
    }
    private func confirmPendingSettings() {
        guard let requested=pendingProfile,let since=pendingSince,store.connected,
              let received=store.phoneAt,received>since,Date().timeIntervalSince(received)<2.5,
              let latest=store.samples.last,latest.time>=received,
              let object=latest.phone["debug"] as? [String:Any],let confirmed=try? DebugSettings.parse(object),confirmed==requested,
              let reserve=latest.value("phone.reserveMs"),let effective=latest.value("phone.bufferMs"),effective>0,
              let buffer=latest.value("phone.requestedBufferMs"),let prefill=latest.value("phone.prefillMs"),prefill>0 else { return }
        if let expected=pendingTrim,let actual=latest.value("mac.macTrimMs"),abs(expected-actual)>0.001 { return }
        pendingProfile=nil; pendingSince=nil; pendingTrim=nil
        feedback.textColor=mint
        feedback.stringValue="Aplicado · reserva \(Int(reserve)) ms · búfer real \(Int(effective)) ms (pedido \(Int(buffer))) · precarga \(Int(prefill)) ms"+(dirty ? " · Borrador sin aplicar." : "")
    }
    func refresh() {
        loadControls(); updateEnabled(); confirmPendingSettings()
        let codec=store.phone["codec"] as? String ?? "—"
        connection.stringValue=store.fixture ? "DEMOSTRACIÓN · Datos simulados para revisar el diseño" : store.connected ? "● Conectado · \(codec.uppercased()) · \(store.profile?.manual == true ? "Manual" : "Automático") · \(paused ? "Vista pausada" : "En vivo · 1 Hz")" : "○ Sin conexión · Conecta Android para medir y ajustar"
        connection.textColor=store.connected ? mint : muted
        if store.connected && store.profile==nil { feedback.stringValue="Esta versión de Android no ofrece ajustes remotos. Actualiza ambas apps." }
        guard !paused else { return }
        latestSamples=store.samples; for chart in charts { chart.samples=latestSamples }
        let latest=latestSamples.last
        func number(_ key:String,_ suffix:String=" ms",_ decimals:Int=0) -> String { latest?.value(key).map { String(format:"%.*f",decimals,$0)+suffix } ?? "—" }
        let values=[number("phone.reserveMs"),number("phone.estimatedLatencyMs"),number("phone.bufferMs","")+" / "+number("phone.requestedBufferMs"),number("phone.macRelativeMs"),number("phone.rttMs"," ms",1),number("phone.totalUnderruns",""),number("phone.queueMs"),number("phone.queuedOutputMs")]
        for (label,value) in zip(summary,values) { label.stringValue=value }
        let fields:[(String,String,String)] = [
            ("Frecuencia Android","phone.rate"," Hz"),("Paquetes recibidos","phone.packets",""),("Bytes recibidos sin cifrado","phone.bytes"," B"),("Reserva objetivo","phone.reserveMs"," ms"),
            ("Búfer solicitado","phone.requestedBufferMs"," ms"),("Búfer efectivo","phone.bufferMs"," ms"),("Precarga","phone.prefillMs"," ms"),("Latencia estimada","phone.estimatedLatencyMs"," ms"),
            ("Desfase objetivo compartido","phone.syncMs"," ms"),("Velocidad (×)","phone.speed",""),("Cortes de sesión","phone.underruns",""),("Cortes totales","phone.totalUnderruns",""),
            ("Reconexiones","phone.reconnects",""),("Bloques en cola","phone.queueChunks",""),("Audio en cola","phone.queueMs"," ms"),("Audio en salida","phone.queuedOutputMs"," ms"),
            ("RTT de red","phone.rttMs"," ms"),("Offset entre relojes","phone.clockOffsetMs"," ms"),("Intervalo paquetes","phone.packetGapMs"," ms"),("Variación llegada","phone.jitterMs"," ms"),
            ("Decodificación","phone.decodeMs"," ms"),("Escritura AudioTrack","phone.writeMs"," ms"),("Recepción","phone.kbps"," kbps"),("Envío Mac cifrado","mac.sendKbps"," kbps"),
            ("Frecuencia captura","mac.captureRate"," Hz"),("Frames capturados","mac.capturedFrames",""),("Retraso de captura","mac.captureLagMs"," ms"),("Desbordes captura","mac.captureOverflowCount",""),
            ("Muestras ≥ 0 dBFS","mac.clippedSamples",""),("Pico capturado","mac.sourcePeakDbfs"," dBFS"),("Codificación","mac.encodeMs"," ms"),("Codificación media","mac.encodeAverageMs"," ms"),
            ("Paquetes enviados","mac.encodedPackets",""),("Bytes enviados audio","mac.encodedBytes"," B"),("Red pendiente Mac","mac.pendingBytes"," B"),("Salida local reportada","mac.localPresentationMs"," ms"),
            ("Ajuste de la Mac","mac.macTrimMs"," ms"),("Reserva local + ajuste","mac.localScheduleMs"," ms"),
            ("Precarga solicitada","phone.requestedPrefillMs"," ms"),("Tasa AAC","phone.bitrateKbps"," kbps"),
            ("Edad del audio al llegar","phone.arrivalAgeMs"," ms"),("RTT inicial","phone.initialRttMs"," ms"),
            ("CPU de Android","phone.cpuPercent"," %"),("Heap Android","phone.heapMb"," MB"),
            ("Señal Wi-Fi","phone.wifiRssiDbm"," dBm"),("Enlace Wi-Fi","phone.wifiLinkSpeedMbps"," Mbps"),
            ("Tiempo de sesión","phone.uptimeMs"," ms"),("Desfase relativo a la Mac","phone.macRelativeMs"," ms"),
            ("Intervalo máximo (1 s)","phone.packetGapMaxMs"," ms"),("Edad máxima audio (1 s)","phone.arrivalAgeMaxMs"," ms"),
            ("Decode máximo (1 s)","phone.decodeMaxMs"," ms"),("Escritura máxima (1 s)","phone.writeMaxMs"," ms"),
            ("Encode máximo (1 s)","mac.encodeMaxMs"," ms"),("Edad medición de desfase","phone.syncAgeMs"," ms")]
        var rows=[String]()
        for i in stride(from:0,to:fields.count,by:2) {
            func cell(_ f:(String,String,String))->String { let value=number(f.1,f.2,f.1=="phone.speed" ? 6 : (f.2==" ms" || f.2==" dBFS" ? 2 : 0)); return (f.0+": "+value).padding(toLength:57,withPad:" ",startingAt:0) }
            rows.append(cell(fields[i])+(i+1<fields.count ? cell(fields[i+1]) : ""))
        }
        measurements.stringValue=rows.joined(separator:"\n")
        let formatter=DateFormatter(); formatter.dateFormat="HH:mm:ss"
        eventText.string=store.events.reversed().map { "\(formatter.string(from:$0.time))  \($0.message)" }.joined(separator:"\n")
        eventText.sizeToFit()
    }
}
