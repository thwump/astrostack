import Foundation

public final class FitsWriter: Sendable {
    public init() {}

    public func writeFits(
        red: [Float],
        green: [Float],
        blue: [Float],
        width: Int,
        height: Int,
        exposureSeconds: Double,
        iso: Float,
        frameCount: Int
    ) -> Data {
        var data = Data()

        // 1. Build FITS Header Cards (80 chars each)
        var cards = [String]()
        cards.append(formatCard(key: "SIMPLE", value: "T", comment: "Standard FITS format"))
        cards.append(formatCard(key: "BITPIX", value: "16", comment: "16-bit unsigned integer data"))
        cards.append(formatCard(key: "NAXIS", value: "3", comment: "3D RGB Cube"))
        cards.append(formatCard(key: "NAXIS1", value: "\(width)", comment: "Width in pixels"))
        cards.append(formatCard(key: "NAXIS2", value: "\(height)", comment: "Height in pixels"))
        cards.append(formatCard(key: "NAXIS3", value: "3", comment: "Color planes (R, G, B)"))
        cards.append(formatCard(key: "BZERO", value: "32768", comment: "Offset for unsigned 16-bit integers"))
        cards.append(formatCard(key: "BSCALE", value: "1", comment: "Linear data scaling"))
        cards.append(formatCard(key: "EXPTIME", value: String(format: "%.2f", exposureSeconds), comment: "Exposure per frame in seconds"))
        cards.append(formatCard(key: "ISO", value: "\(Int(iso))", comment: "Sensor ISO gain"))
        cards.append(formatCard(key: "STACKNUM", value: "\(frameCount)", comment: "Total integrated frames"))
        cards.append(formatCard(key: "PROGRAM", value: "'AstroStack iOS'", comment: "Astrophotography Stacking App"))
        cards.append(formatCard(key: "END", value: nil, comment: nil))

        var headerString = cards.joined()
        // Pad header to multiple of 2880 bytes with ASCII spaces (0x20)
        let padSize = (2880 - (headerString.count % 2880)) % 2880
        if padSize > 0 {
            headerString += String(repeating: " ", count: padSize)
        }

        guard let headerData = headerString.data(using: .ascii) else { return Data() }
        data.append(headerData)

        // 2. Encode 16-bit Big-Endian Pixel Data (R, G, B planes)
        let planes = [red, green, blue]
        for plane in planes {
            for val in plane {
                let clamped = max(0.0, min(1.0, val))
                let u16 = UInt16(clamped * 65535.0)
                let be = u16.bigEndian
                withUnsafeBytes(of: be) { data.append(contentsOf: $0) }
            }
        }

        // Pad data to multiple of 2880 bytes with zero bytes (0x00)
        let dataPadSize = (2880 - (data.count % 2880)) % 2880
        if dataPadSize > 0 {
            data.append(Data(repeating: 0, count: dataPadSize))
        }

        return data
    }

    private func formatCard(key: String, value: String?, comment: String?) -> String {
        if key == "END" {
            return "END" + String(repeating: " ", count: 77)
        }

        let keyPadded = key.padding(toLength: 8, withPad: " ", startingAt: 0)
        guard let val = value else {
            return keyPadded + String(repeating: " ", count: 72)
        }

        let valFormatted = val.hasPrefix("'") ? val.padding(toLength: 20, withPad: " ", startingAt: 0) : String(repeating: " ", count: max(0, 20 - val.count)) + val
        var card = "\(keyPadded)= \(valFormatted)"

        if let com = comment {
            card += " / \(com)"
        }

        return card.padding(toLength: 80, withPad: " ", startingAt: 0)
    }
}
