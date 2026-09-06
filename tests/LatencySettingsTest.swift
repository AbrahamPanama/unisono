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
        print("PASS: custom reserve parsing, trim validation, saved values, profile/recovery/legacy negotiation.")
    }
}
