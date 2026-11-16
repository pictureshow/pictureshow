// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation

/// A JSON tree that keeps every primitive's exact text, so 64-bit ticks never pass through a Double.
/// Primitive reads follow the reference parser: a primitive's content is its text whether quoted or not,
/// so a quoted "50000000" reads as a number and an unquoted 1.5 never reads as an integer.
public indirect enum SyncJSON: Equatable, Sendable {
    case object([(String, SyncJSON)])
    case array([SyncJSON])
    case string(String)
    /// A number, `true` or `false`, as written.
    case literal(String)
    case null

    public static func == (a: SyncJSON, b: SyncJSON) -> Bool {
        switch (a, b) {
        case let (.object(x), .object(y)): return x.count == y.count && zip(x, y).allSatisfy { $0.0 == $1.0 && $0.1 == $1.1 }
        case let (.array(x), .array(y)): return x == y
        case let (.string(x), .string(y)): return x == y
        case let (.literal(x), .literal(y)): return x == y
        case (.null, .null): return true
        default: return false
        }
    }

    public subscript(key: String) -> SyncJSON? {
        guard case let .object(pairs) = self else { return nil }
        return pairs.last { $0.0 == key }?.1
    }
    public var keys: [String] { if case let .object(pairs) = self { return pairs.map(\.0) }; return [] }
    public var isObject: Bool { if case .object = self { return true }; return false }
    public var isNull: Bool { self == .null }
    public var isString: Bool { if case .string = self { return true }; return false }
    public var items: [SyncJSON]? { if case let .array(values) = self { return values }; return nil }

    /// Primitive content; nil for null, objects and arrays.
    public var content: String? {
        switch self {
        case let .string(s), let .literal(s): return s
        default: return nil
        }
    }
    /// A whole decimal 64-bit integer, without sign prefix, fraction or exponent.
    public var int64: Int64? {
        guard let text = content, !text.isEmpty else { return nil }
        let digits = text.hasPrefix("-") ? text.dropFirst() : Substring(text)
        guard !digits.isEmpty, digits.allSatisfy({ $0.isASCII && $0.isNumber }) else { return nil }
        return Int64(text)
    }
    public var int32: Int? { int64.flatMap { $0 >= Int64(Int32.min) && $0 <= Int64(Int32.max) ? Int($0) : nil } }
    public var bool: Bool? { content == "true" ? true : content == "false" ? false : nil }
    public var double: Double? {
        guard let text = content, !text.isEmpty, text.allSatisfy({ $0.isASCII && ($0.isNumber || "+-.eE".contains($0)) }) else { return nil }
        return Double(text)
    }

    public static func parse(_ text: String) throws -> SyncJSON {
        var parser = Parser(bytes: Array(text.utf8))
        parser.whitespace()
        let value = try parser.value(depth: 0)
        parser.whitespace()
        guard parser.position == parser.bytes.count else { throw SyncJSONError.malformed }
        return value
    }
    public static func parse(_ data: Data) throws -> SyncJSON {
        guard let text = String(data: data, encoding: .utf8) else { throw SyncJSONError.malformed }
        return try parse(text)
    }

    public var rendered: String {
        switch self {
        case let .object(pairs): return "{" + pairs.map { Self.quote($0.0) + ":" + $0.1.rendered }.joined(separator: ",") + "}"
        case let .array(values): return "[" + values.map(\.rendered).joined(separator: ",") + "]"
        case let .string(s): return Self.quote(s)
        case let .literal(s): return s
        case .null: return "null"
        }
    }

    static func quote(_ s: String) -> String {
        var out = "\""
        for scalar in s.unicodeScalars {
            switch scalar {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            default:
                if scalar.value < 0x20 { out += String(format: "\\u%04x", scalar.value) } else { out.unicodeScalars.append(scalar) }
            }
        }
        return out + "\""
    }

    private struct Parser {
        let bytes: [UInt8]
        var position = 0

        mutating func whitespace() { while position < bytes.count, [0x20, 0x09, 0x0A, 0x0D].contains(bytes[position]) { position += 1 } }

        mutating func value(depth: Int) throws -> SyncJSON {
            guard depth < 64, position < bytes.count else { throw SyncJSONError.malformed }
            switch bytes[position] {
            case UInt8(ascii: "{"):
                position += 1; whitespace()
                var pairs: [(String, SyncJSON)] = []
                if position < bytes.count, bytes[position] == UInt8(ascii: "}") { position += 1; return .object(pairs) }
                while true {
                    whitespace()
                    let key = try string()
                    whitespace()
                    guard position < bytes.count, bytes[position] == UInt8(ascii: ":") else { throw SyncJSONError.malformed }
                    position += 1; whitespace()
                    pairs.append((key, try value(depth: depth + 1)))
                    whitespace()
                    guard position < bytes.count else { throw SyncJSONError.malformed }
                    if bytes[position] == UInt8(ascii: ",") { position += 1; continue }
                    if bytes[position] == UInt8(ascii: "}") { position += 1; return .object(pairs) }
                    throw SyncJSONError.malformed
                }
            case UInt8(ascii: "["):
                position += 1; whitespace()
                var values: [SyncJSON] = []
                if position < bytes.count, bytes[position] == UInt8(ascii: "]") { position += 1; return .array(values) }
                while true {
                    whitespace()
                    values.append(try value(depth: depth + 1))
                    whitespace()
                    guard position < bytes.count else { throw SyncJSONError.malformed }
                    if bytes[position] == UInt8(ascii: ",") { position += 1; continue }
                    if bytes[position] == UInt8(ascii: "]") { position += 1; return .array(values) }
                    throw SyncJSONError.malformed
                }
            case UInt8(ascii: "\""): return .string(try string())
            default:
                let start = position
                while position < bytes.count, !",]} \t\r\n".utf8.contains(bytes[position]) { position += 1 }
                guard let word = String(bytes: bytes[start..<position], encoding: .utf8), !word.isEmpty else { throw SyncJSONError.malformed }
                if word == "null" { return .null }
                if word == "true" || word == "false" { return .literal(word) }
                guard word.range(of: "^-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?$", options: .regularExpression) != nil else {
                    throw SyncJSONError.malformed
                }
                return .literal(word)
            }
        }

        mutating func string() throws -> String {
            guard position < bytes.count, bytes[position] == UInt8(ascii: "\"") else { throw SyncJSONError.malformed }
            position += 1
            var scalars = String.UnicodeScalarView()
            var raw: [UInt8] = []
            func flush() throws {
                guard !raw.isEmpty else { return }
                guard let text = String(bytes: raw, encoding: .utf8) else { throw SyncJSONError.malformed }
                scalars.append(contentsOf: text.unicodeScalars); raw.removeAll()
            }
            while position < bytes.count {
                let byte = bytes[position]
                if byte == UInt8(ascii: "\"") { position += 1; try flush(); return String(scalars) }
                if byte < 0x20 { throw SyncJSONError.malformed }
                if byte == UInt8(ascii: "\\") {
                    try flush()
                    position += 1
                    guard position < bytes.count else { throw SyncJSONError.malformed }
                    let escaped = bytes[position]; position += 1
                    switch escaped {
                    case UInt8(ascii: "\""): scalars.append("\"")
                    case UInt8(ascii: "\\"): scalars.append("\\")
                    case UInt8(ascii: "/"): scalars.append("/")
                    case UInt8(ascii: "b"): scalars.append("\u{08}")
                    case UInt8(ascii: "f"): scalars.append("\u{0C}")
                    case UInt8(ascii: "n"): scalars.append("\n")
                    case UInt8(ascii: "r"): scalars.append("\r")
                    case UInt8(ascii: "t"): scalars.append("\t")
                    case UInt8(ascii: "u"):
                        var unit = try hex()
                        if (0xD800...0xDBFF).contains(unit), position + 1 < bytes.count, bytes[position] == UInt8(ascii: "\\"), bytes[position + 1] == UInt8(ascii: "u") {
                            position += 2
                            let low = try hex()
                            guard (0xDC00...0xDFFF).contains(low) else { throw SyncJSONError.malformed }
                            unit = 0x10000 + ((unit - 0xD800) << 10) + (low - 0xDC00)
                        }
                        guard let scalar = Unicode.Scalar(unit) else { throw SyncJSONError.malformed }
                        scalars.append(scalar)
                    default: throw SyncJSONError.malformed
                    }
                    continue
                }
                raw.append(byte); position += 1
            }
            throw SyncJSONError.malformed
        }

        mutating func hex() throws -> UInt32 {
            guard position + 4 <= bytes.count, let text = String(bytes: bytes[position..<position + 4], encoding: .ascii),
                  let value = UInt32(text, radix: 16) else { throw SyncJSONError.malformed }
            position += 4
            return value
        }
    }
}

public enum SyncJSONError: Error { case malformed }
