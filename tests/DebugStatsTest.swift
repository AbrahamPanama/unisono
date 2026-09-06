import Foundation
@main enum DebugStatsTest {
    static func main() throws {
        let text="Connection to 192.168.1.44:45871, fe80::1234%en0, fe80::5678%wlan0, unisono://10.0.0.2:45871#0123456789abcdef0123456789abcdef; token fedcba9876543210fedcba9876543210 /Users/Someone/test"
        let sanitized=DebugStats.safeMessage(text)
        for privateValue in ["192.168.1.44","fe80::1234","%en0","%wlan0","unisono://","0123456789abcdef","fedcba9876543210","Someone"] { precondition(!sanitized.contains(privateValue),"Redaction failed: \(privateValue)") }
        let stats=DebugStats();stats.acceptPhone(["syncMs":20,"reserveMs":250,"device":"Private Device","pairKey":"secret","routeType":"Personal headphones","debug":DebugSettings.defaults.json]);stats.tick(mac:["localEnabled":true,"macTrimMs":100,"captureLagMs":3],streaming:true)
        precondition(stats.samples.last!.value("phone.macRelativeMs")==(-80))
        stats.addEvent("failure",text,settings:.defaults)
        let exported=String(data:try stats.export(),encoding:.utf8)!
        precondition(!exported.contains("Private Device") && !exported.contains("Personal headphones") && !exported.contains("secret"))
        precondition(exported.contains("lastSample") && exported.contains("message") && exported.contains("settings"))
        precondition(!exported.contains("192.168.1.44"))
        // Local playback disabled and stale phone telemetry must yield missing values, never an invented zero.
        stats.tick(mac:["localEnabled":false,"macTrimMs":100],streaming:true)
        precondition(stats.samples.last!.value("phone.macRelativeMs")==nil)
        stats.tick(mac:["localEnabled":true,"macTrimMs":100],streaming:true,at:Date().addingTimeInterval(3))
        precondition(stats.samples.last!.value("phone.syncMs")==nil)
        // A route event identifies the route at event time, even when the enclosing sample is stale.
        let routes=DebugStats()
        routes.acceptPhone(["routeType":"unknown","events":[["timeMs":1,"code":"route","message":"Salida: bluetooth"]]])
        precondition(routes.events.last?.message=="Android · Salida: bluetooth")
        routes.acceptPhone(["routeType":"usb","events":[["timeMs":2,"code":"route","message":"Salida: Personal headphones"]]])
        precondition(routes.events.last?.message=="Android · Salida: usb")
        let routeExport=String(data:try routes.export(),encoding:.utf8)!
        precondition(!routeExport.contains("Personal headphones"))
        // Histories remain bounded during long debugging sessions.
        for _ in 0..<610 { stats.tick(mac:[:],streaming:false) }
        for i in 0..<210 { stats.addEvent("event",String(i)) }
        precondition(stats.samples.count==600 && stats.events.count==200)
        print("Diagnostics redaction, relative timing, routes, gaps and bounded history: PASS")
    }
}
