import Foundation

@main struct LatencySettingsTest {
    static func main() {
        precondition(LatencySettings.parseReserve("250") == 250)
        precondition(LatencySettings.parseReserve(" 625 ") == 625)
        precondition(LatencySettings.parseReserve("1000") == 1000)
        for invalid in ["", "abc", "nan", "inf", "249", "1001", "250.5"] {
            precondition(LatencySettings.parseReserve(invalid) == nil, "Invalid reserve accepted: \(invalid)")
        }
        precondition(LatencySettings.parseTrim("250") == nil, "Local trim must not silently clamp 250 to 100")
        precondition(LatencySettings.parseTrim("-100") == -100)
        precondition(LatencySettings.parseTrim("1,5") == 1.5)
        precondition(LatencySettings.parseTrim("nan") == nil)
        precondition(LatencySettings.savedMilliseconds(seconds: 0) == 500)
        precondition(LatencySettings.savedMilliseconds(seconds: 0.25) == 250)
        precondition(LatencySettings.savedMilliseconds(seconds: .nan) == 500)
        precondition(LatencySettings.negotiate(macMilliseconds: 250, receiverMilliseconds: 250) == 250)
        precondition(LatencySettings.negotiate(macMilliseconds: 500, receiverMilliseconds: 250) == 500)
        precondition(LatencySettings.negotiate(macMilliseconds: 250, receiverMilliseconds: 750) == 750)
        precondition(LatencySettings.negotiate(macMilliseconds: 625, receiverMilliseconds: 500) == 625)
        precondition(LatencySettings.negotiate(macMilliseconds: 250, receiverMilliseconds: 500) == 500, "Older receivers retain their floor")
        precondition(LatencySettings.negotiate(macMilliseconds: 250, receiverMilliseconds: nil) == 250)
        precondition(LatencySettings.negotiate(macMilliseconds: 250, receiverMilliseconds: 2000) == 1000)
        let manual=DebugSettings(manual:true,reserveMs:125,bufferMs:80,prefillMs:40,clockCorrection:false,retry:false)
        precondition(manual.reserve(macMilliseconds:1000,receiverMilliseconds:750)==125,"Manual reserve must bypass both automatic floors")
        precondition(manual.reserve(macMilliseconds:250,receiverMilliseconds:nil)==125)
        do { let roundTrip=try DebugSettings.parse(manual.json); precondition(roundTrip==manual) } catch { preconditionFailure("Valid manual settings rejected") }
        let automatic=DebugSettings(manual:false,reserveMs:125,bufferMs:80,prefillMs:40,clockCorrection:false,retry:false)
        precondition(automatic.reserve(macMilliseconds:500,receiverMilliseconds:750)==750,"Auto retains profile floor")
        for (key,value) in [("reserveMs",99 as Any),("reserveMs",2001),("reserveMs",250.5),("reserveMs",true),("manual",1),("bufferMs",19),("bufferMs",501),("prefillMs",9),("prefillMs",81),("clockCorrection","false")] {
            var invalid=manual.json; invalid[key]=value
            do { _=try DebugSettings.parse(invalid); preconditionFailure("Invalid debugging value accepted: \(key)") } catch {}
        }
        var missing=manual.json; missing.removeValue(forKey:"retry")
        do { _=try DebugSettings.parse(missing); preconditionFailure("Incomplete debugging settings accepted") } catch {}
        print("PASS: custom reserve parsing, trim validation, legacy negotiation, exact manual reserve and strict debug settings.")
    }
}
