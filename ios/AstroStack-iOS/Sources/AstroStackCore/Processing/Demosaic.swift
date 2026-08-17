import Foundation

public enum BayerPattern: Sendable {
    case rggb
    case bggr
    case grbg
    case gbrg
}

public final class Demosaic: Sendable {
    public init() {}

    public struct RgbPlanes: Sendable {
        public let r: [Float]
        public let g: [Float]
        public let b: [Float]
        public let width: Int
        public let height: Int

        public init(r: [Float], g: [Float], b: [Float], width: Int, height: Int) {
            self.r = r
            self.g = g
            self.b = b
            self.width = width
            self.height = height
        }
    }

    public func demosaicBilinear(
        raw: [Float],
        width: Int,
        height: Int,
        pattern: BayerPattern = .rggb
    ) -> RgbPlanes {
        guard raw.count == width * height else {
            let empty = [Float](repeating: 0, count: width * height)
            return RgbPlanes(r: empty, g: empty, b: empty, width: width, height: height)
        }

        var outR = [Float](repeating: 0, count: width * height)
        var outG = [Float](repeating: 0, count: width * height)
        var outB = [Float](repeating: 0, count: width * height)

        for y in 1..<(height - 1) {
            let rowOffset = y * width
            let rowTop = (y - 1) * width
            let rowBot = (y + 1) * width

            for x in 1..<(width - 1) {
                let idx = rowOffset + x
                let isEvenRow = (y % 2 == 0)
                let isEvenCol = (x % 2 == 0)

                switch pattern {
                case .rggb:
                    if isEvenRow && isEvenCol {
                        // Red pixel
                        outR[idx] = raw[idx]
                        outG[idx] = 0.25 * (raw[rowTop + x] + raw[rowBot + x] + raw[rowOffset + x - 1] + raw[rowOffset + x + 1])
                        outB[idx] = 0.25 * (raw[rowTop + x - 1] + raw[rowTop + x + 1] + raw[rowBot + x - 1] + raw[rowBot + x + 1])
                    } else if !isEvenRow && !isEvenCol {
                        // Blue pixel
                        outB[idx] = raw[idx]
                        outG[idx] = 0.25 * (raw[rowTop + x] + raw[rowBot + x] + raw[rowOffset + x - 1] + raw[rowOffset + x + 1])
                        outR[idx] = 0.25 * (raw[rowTop + x - 1] + raw[rowTop + x + 1] + raw[rowBot + x - 1] + raw[rowBot + x + 1])
                    } else {
                        // Green pixel
                        outG[idx] = raw[idx]
                        if isEvenRow {
                            outR[idx] = 0.5 * (raw[rowOffset + x - 1] + raw[rowOffset + x + 1])
                            outB[idx] = 0.5 * (raw[rowTop + x] + raw[rowBot + x])
                        } else {
                            outR[idx] = 0.5 * (raw[rowTop + x] + raw[rowBot + x])
                            outB[idx] = 0.5 * (raw[rowOffset + x - 1] + raw[rowOffset + x + 1])
                        }
                    }
                default:
                    // Default fallback
                    outR[idx] = raw[idx]
                    outG[idx] = raw[idx]
                    outB[idx] = raw[idx]
                }
            }
        }

        return RgbPlanes(r: outR, g: outG, b: outB, width: width, height: height)
    }
}
