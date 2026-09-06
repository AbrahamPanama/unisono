import Foundation
import CoreFoundation

struct DebugSettings: Equatable {
    var manual: Bool
    var reserveMs: Int
    var bufferMs: Int
    var prefillMs: Int
    var clockCorrection: Bool
    var retry: Bool

    static let defaults = DebugSettings(manual: false, reserveMs: 750, bufferMs: 100, prefillMs: 100, clockCorrection: true, retry: true)
    enum Invalid: LocalizedError {
        case configuration
        var errorDescription: String? { "Depuración: usa reserva 100–2000 ms, búfer 20–500 ms y precarga 10–200 ms (no mayor al búfer)." }
    }
    static func parse(_ object: [String: Any]) throws -> DebugSettings {
        func integer(_ key: String, _ range: ClosedRange<Int>) throws -> Int {
            guard let value = object[key] as? NSNumber, CFGetTypeID(value) != CFBooleanGetTypeID(), value.doubleValue.isFinite,
                  value.doubleValue.rounded() == value.doubleValue, range.contains(value.intValue) else { throw Invalid.configuration }
            return value.intValue
        }
        func boolean(_ key: String) throws -> Bool {
            guard let value = object[key] as? NSNumber, CFGetTypeID(value) == CFBooleanGetTypeID() else { throw Invalid.configuration }
            return value.boolValue
        }
        let settings = try DebugSettings(manual: boolean("manual"), reserveMs: integer("reserveMs", 100...2000), bufferMs: integer("bufferMs", 20...500), prefillMs: integer("prefillMs", 10...200), clockCorrection: boolean("clockCorrection"), retry: boolean("retry"))
        guard settings.prefillMs <= settings.bufferMs else { throw Invalid.configuration }
        return settings
    }
    var json: [String: Any] { ["manual": manual, "reserveMs": reserveMs, "bufferMs": bufferMs, "prefillMs": prefillMs, "clockCorrection": clockCorrection, "retry": retry] }
    func reserve(macMilliseconds: Int, receiverMilliseconds: Int?) -> Int {
        manual ? reserveMs : LatencySettings.negotiate(macMilliseconds: macMilliseconds, receiverMilliseconds: receiverMilliseconds)
    }
}
