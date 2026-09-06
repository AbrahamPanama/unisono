import Foundation

enum LatencySettings {
    static let minimum = 250
    static let maximum = 1000
    static let defaultMilliseconds = 500

    static func savedMilliseconds(seconds: Double) -> Int {
        guard seconds.isFinite, seconds >= 0.25, seconds <= 1 else { return defaultMilliseconds }
        return Int((seconds * 1000).rounded())
    }
    static func parseReserve(_ text: String) -> Int? {
        guard let value = number(text), value >= Double(minimum), value <= Double(maximum), value.rounded() == value else { return nil }
        return Int(value)
    }
    static func parseTrim(_ text: String) -> Double? {
        guard let value = number(text), (-100...100).contains(value) else { return nil }
        return value
    }
    private static func number(_ text: String) -> Double? {
        guard let value = Double(text.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: ",", with: ".")), value.isFinite else { return nil }
        return value
    }
    static func negotiate(macMilliseconds: Int, receiverMilliseconds: Int?) -> Int {
        let mac = max(minimum, min(maximum, macMilliseconds))
        let receiver = max(minimum, min(maximum, receiverMilliseconds ?? minimum))
        return max(mac, receiver)
    }
}
