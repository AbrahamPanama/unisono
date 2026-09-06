import Foundation
import CoreFoundation

struct DebugSample {
    let time: Date
    let phone: [String:Any]
    let mac: [String:Any]
    func value(_ key: String) -> Double? {
        let parts=key.split(separator:".",maxSplits:1)
        guard parts.count==2 else { return nil }
        let value=(parts[0]=="mac" ? mac : phone)[String(parts[1])] as? NSNumber
        guard let v=value?.doubleValue,v.isFinite else { return nil }; return v
    }
}
struct DebugEvent {
    let time: Date
    let code: String
    let message: String
    let settings: DebugSettings?
    let macTrimMs: Double?
    let lastSample: [String:Double]?
}
final class DebugStats {
    static let routeTypes:Set<String> = ["speaker","earpiece","wired","bluetooth","bluetooth-le","usb","hdmi","unknown","other"]
    static let phoneNumbers:Set<String> = ["rate","reserveMs","requestedBufferMs","bufferMs","prefillMs","syncMs","syncAgeMs","macRelativeMs","estimatedLatencyMs","speed","underruns","totalUnderruns","reconnects","packets","bytes","kbps","packetGapMs","jitterMs","queueMs","queueChunks","decodeMs","writeMs","queuedOutputMs","rttMs","clockOffsetMs","requestedPrefillMs","bitrateKbps","arrivalAgeMs","initialRttMs","uptimeMs","cpuPercent","heapMb","wifiRssiDbm","wifiLinkSpeedMbps","macTelemetryAgeMs","packetGapMaxMs","decodeMaxMs","writeMaxMs","arrivalAgeMaxMs"]
    static let macNumbers:Set<String> = ["captureRate","capturedFrames","captureLagMs","captureOverflowCount","sourcePeakDbfs","clippedSamples","encodeMs","encodeMaxMs","encodeAverageMs","encodedPackets","encodedBytes","sendKbps","pendingBytes","localPresentationMs","macTrimMs","localScheduleMs","macTimeMs"]
    var samples=[DebugSample]()
    var events=[DebugEvent]()
    var phone=[String:Any](), mac=[String:Any]()
    var phoneAt:Date?, connected=false
    var profile:DebugSettings?
    var profileRevision=0
    var eventIDs=Set<String>()
    var fixture=false
    func acceptPhone(_ object:[String:Any]) {
        var clean=[String:Any]()
        for key in Self.phoneNumbers { if let n=object[key] as? NSNumber,CFGetTypeID(n) != CFBooleanGetTypeID(),n.doubleValue.isFinite { clean[key]=n } }
        for key in ["connected","clockCorrectionEnabled","retryEnabled"] { if let b=object[key] as? Bool { clean[key]=b } }
        if let codec=object["codec"] as? String,["flac","aac-lc","float32le","pcm"].contains(codec) { clean["codec"]=codec }
        if let mode=object["mode"] as? String,["manual","auto","automatic"].contains(mode) { clean["mode"]=mode }
        // Route types are generic platform categories; device names and network addresses are never kept.
        if let route=object["routeType"] as? String,Self.routeTypes.contains(route) { clean["routeType"]=route }
        if let d=object["debug"] as? [String:Any],let parsed=try? DebugSettings.parse(d) { setProfile(parsed); clean["debug"]=parsed.json }
        phone=clean; phoneAt=Date(); connected=true
        if let incoming=object["events"] as? [[String:Any]] {
            for event in incoming {
                let code=(event["code"] as? String) ?? (event["type"] as? String) ?? "receiver"
                guard code.count<64,code.range(of:"^[A-Za-z0-9_.-]+$",options:.regularExpression) != nil else { continue }
                let rawTS=(event["timeMs"] as? NSNumber)?.doubleValue ?? 0
                let ts=rawTS.isFinite ? rawTS : 0; let id="\(ts):\(code)"
                guard !eventIDs.contains(id) else { continue }; eventIDs.insert(id)
                let description=(event["message"] as? String) ?? (event["detail"] as? String) ?? code
                let settings=(event["settings"] as? [String:Any]).flatMap { try? DebugSettings.parse($0) }
                let lastSample=(event["lastSample"] as? [String:Any]).map { Self.numbers($0) }
                var text=description
                if code=="route" {
                    let prefix="Salida:"
                    let candidate=description.hasPrefix(prefix) ? String(description.dropFirst(prefix.count)).trimmingCharacters(in:.whitespacesAndNewlines) : ""
                    let route=Self.routeTypes.contains(candidate) ? candidate : (clean["routeType"] as? String ?? "unknown")
                    text="Salida: \(route)"
                }
                addEvent(code,"Android · "+String(text.prefix(220)),at:ts>0 ? Date(timeIntervalSince1970:ts/1000) : Date(),settings:settings,lastSample:lastSample)
            }
            if eventIDs.count>400 { eventIDs=Set(incoming.map { "\(($0["timeMs"] as? NSNumber)?.doubleValue ?? 0):\(($0["code"] as? String) ?? ($0["type"] as? String) ?? "receiver")" }) }
        }
    }
    func setProfile(_ value:DebugSettings) { if profile != value { profile=value; profileRevision+=1 } }
    func tick(mac object:[String:Any],streaming:Bool,at date:Date=Date()) {
        var clean=[String:Any]()
        for key in Self.macNumbers { if let n=object[key] as? NSNumber,CFGetTypeID(n) != CFBooleanGetTypeID(),n.doubleValue.isFinite { clean[key]=n } }
        for key in ["localEnabled","streaming"] { if let b=object[key] as? Bool { clean[key]=b } }
        mac=clean; connected=streaming
        let fresh=streaming && phoneAt.map { date.timeIntervalSince($0)<2.5 } == true
        var currentPhone=fresh ? phone : [:]
        if fresh,clean["localEnabled"] as? Bool == true,let sync=(currentPhone["syncMs"] as? NSNumber)?.doubleValue,let trim=(clean["macTrimMs"] as? NSNumber)?.doubleValue { currentPhone["macRelativeMs"]=sync-trim }
        else { currentPhone.removeValue(forKey:"macRelativeMs") }
        samples.append(DebugSample(time:date,phone:currentPhone,mac:streaming ? clean : [:]))
        if samples.count>600 { samples.removeFirst(samples.count-600) }
    }
    static func numbers(_ object:[String:Any]) -> [String:Double] {
        var values=[String:Double]()
        for key in phoneNumbers.union(macNumbers).union(["timeMs","monotonicMs"]) { if let value=object[key] as? NSNumber,CFGetTypeID(value) != CFBooleanGetTypeID(),value.doubleValue.isFinite { values[key]=value.doubleValue } }
        return values
    }
    static func safeMessage(_ message:String) -> String {
        var safe=message
        for (pattern,replacement) in [
            ("(?i)unisono://\\S+","[enlace privado]"),
            ("\\b(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d+)?\\b","[dirección]"),
            ("(?i)(?<![a-z0-9])(?:[0-9a-f]{0,4}:){2,}[0-9a-f:]{0,39}(?:%[a-z0-9_.~-]+)?(?![a-z0-9])","[dirección]"),
            ("(?i)\\b[0-9a-f]{32,}\\b","[clave privada]"),
            ("/Users/[^/\\s]+","/Users/[usuario]")
        ] { safe=safe.replacingOccurrences(of:pattern,with:replacement,options:.regularExpression) }
        return String(safe.prefix(300))
    }
    func addEvent(_ code:String,_ message:String,at date:Date=Date(),settings:DebugSettings?=nil,macTrimMs:Double?=nil,lastSample:[String:Double]?=nil) {
        var snapshot=lastSample
        if snapshot==nil,let latest=samples.last {
            snapshot=Self.numbers(latest.phone).merging(Self.numbers(latest.mac),uniquingKeysWith: { phone,_ in phone }); snapshot?["sampleTimeMs"]=latest.time.timeIntervalSince1970*1000
        }
        events.append(DebugEvent(time:date,code:code,message:Self.safeMessage(message),settings:settings,macTrimMs:macTrimMs,lastSample:snapshot)); if events.count>200 { events.removeFirst(events.count-200) }
    }
    func clear() { samples.removeAll(); events.removeAll(); addEvent("history_cleared","Historial borrado; la captura de estadísticas continúa.") }
    func export() throws -> Data {
        // Export allowlisted measurements and bounded event descriptions after removing network and pairing identifiers.
        let payload:[String:Any] = ["schemaVersion":1,"application":"Unisono","demo":fixture,"exportedAt":ISO8601DateFormatter().string(from:Date()),
            "notes":"Software timing estimates, not measured acoustic latency. Missing measurements are absent, never zero. Event messages are redacted; device/network identifiers are omitted.",
            "samples":samples.map { ["timeMs":$0.time.timeIntervalSince1970*1000,"phone":$0.phone,"mac":$0.mac] as [String:Any] },
            "events":events.map { event -> [String:Any] in
                var item:[String:Any] = ["timeMs":event.time.timeIntervalSince1970*1000,"code":event.code,"message":Self.safeMessage(event.message)]
                if let sample=event.lastSample { item["lastSample"]=sample }
                if let settings=event.settings { item["settings"]=settings.json }
                if let trim=event.macTrimMs,trim.isFinite { item["macTrimMs"]=trim }; return item
            }]
        return try JSONSerialization.data(withJSONObject:payload,options:[.prettyPrinted,.sortedKeys])
    }
    func loadFixture() {
        fixture=true; setProfile(DebugSettings(manual:true,reserveMs:250,bufferMs:100,prefillMs:80,clockCorrection:false,retry:false)); let now=Date()
        for i in 0..<180 {
            let t=Double(i); let phone:[String:Any] = ["codec":"flac","mode":"manual","rate":48000,"reserveMs":250,"requestedBufferMs":100,"bufferMs":200,"prefillMs":80,"syncMs":13+sin(t/18)*4,"macRelativeMs":13+sin(t/18)*4,"estimatedLatencyMs":263+sin(t/18)*4,"speed":1.0,"underruns":0,"totalUnderruns":0,"reconnects":0,"packets":Int(t*47),"bytes":Int(t*120000),"kbps":920+sin(t/12)*100,"packetGapMs":21+sin(t/3)*4,"jitterMs":2+abs(sin(t/8))*3,"queueMs":78+sin(t/7)*12,"queueChunks":4,"decodeMs":0.5+abs(sin(t/9))*0.3,"decodeMaxMs":1+abs(sin(t/9))*0.8,"writeMaxMs":5+abs(sin(t/6))*4,"packetGapMaxMs":22+abs(sin(t/3))*5,"arrivalAgeMaxMs":12+abs(sin(t/6))*4,"writeMs":2+abs(sin(t/6))*3,"queuedOutputMs":170+sin(t/10)*10,"rttMs":3+abs(sin(t/7))*2,"clockOffsetMs":128.4,"routeType":"USB","debug":profile!.json]
            let mac:[String:Any] = ["captureRate":48000,"capturedFrames":i*48000,"captureLagMs":3+abs(sin(t/8))*2,"captureOverflowCount":0,"sourcePeakDbfs": -8+sin(t/5)*3,"clippedSamples":0,"encodeMs":0.7+abs(sin(t/5))*0.3,"encodeAverageMs":0.8,"encodeMaxMs":1+abs(sin(t/5))*0.5,"encodedPackets":i*47,"encodedBytes":i*120000,"sendKbps":950+sin(t/12)*100,"pendingBytes":0,"localPresentationMs":12,"macTrimMs":0,"localScheduleMs":250,"localEnabled":true,"streaming":true]
            samples.append(DebugSample(time:now.addingTimeInterval(t-179),phone:(i>=80 && i<85) ? [:] : phone,mac:mac)); self.phone=phone; self.mac=mac
        }
        phoneAt=now; connected=true
        addEvent("manual_applied","DEMOSTRACIÓN · Reserva fija 250 ms · Corrección de reloj desactivada",at:now.addingTimeInterval(-180))
        addEvent("telemetry_gap","DEMOSTRACIÓN · Cinco segundos sin telemetría: el gráfico deja un hueco",at:now.addingTimeInterval(-99))
    }
}
