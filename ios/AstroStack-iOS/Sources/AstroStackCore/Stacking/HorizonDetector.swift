import Foundation

public final class HorizonDetector: Sendable {
    public init() {}

    public struct HorizonResult: Equatable, Sendable {
        public let horizonRow: Int
        public let confidence: Float
        public let hasHorizon: Bool

        public init(horizonRow: Int, confidence: Float, hasHorizon: Bool) {
            self.horizonRow = horizonRow
            self.confidence = confidence
            self.hasHorizon = hasHorizon
        }
    }

    // MARK: - Horizon Detection
    public func detectHorizon(luminance: [Float], width: Int, height: Int) -> HorizonResult {
        guard width > 20, height > 20, luminance.count == width * height else {
            return HorizonResult(horizonRow: height / 2, confidence: 0, hasHorizon: false)
        }

        // Search in middle 60% of the image (20% to 80% vertical range)
        let startRow = Int(Double(height) * 0.20)
        let endRow = Int(Double(height) * 0.80)

        var rowGrads = [Float](repeating: 0, count: height)
        var maxGrad: Float = 0
        var bestRow = height / 2

        for y in startRow..<endRow {
            var sumGrad: Float = 0
            let rTop = (y - 1) * width
            let rBot = (y + 1) * width

            for x in stride(from: 5, to: width - 5, by: 4) {
                let diff = abs(luminance[rBot + x] - luminance[rTop + x])
                sumGrad += diff
            }

            let avgGrad = sumGrad / Float(width / 4)
            rowGrads[y] = avgGrad

            if avgGrad > maxGrad {
                maxGrad = avgGrad
                bestRow = y
            }
        }

        // Calculate confidence relative to overall gradient variance
        var meanGrad: Float = 0
        for y in startRow..<endRow { meanGrad += rowGrads[y] }
        meanGrad /= Float(endRow - startRow)

        let peakRatio = meanGrad > 0 ? (maxGrad / meanGrad) : 1.0
        let hasHorizon = peakRatio > 2.2 && maxGrad > 0.04

        return HorizonResult(
            horizonRow: bestRow,
            confidence: min(1.0, peakRatio / 5.0),
            hasHorizon: hasHorizon
        )
    }

    // MARK: - Feathered Alpha Mask
    public func generateAlphaMask(height: Int, horizonRow: Int, featherRadius: Int = 30) -> [Float] {
        var mask = [Float](repeating: 0, count: height)

        let top = max(0, horizonRow - featherRadius)
        let bottom = min(height - 1, horizonRow + featherRadius)

        for y in 0..<height {
            if y <= top {
                mask[y] = 1.0 // 100% Sky
            } else if y >= bottom {
                mask[y] = 0.0 // 100% Ground
            } else {
                // Cosine smooth transition
                let t = Float(y - top) / Float(bottom - top)
                mask[y] = 0.5 * (1.0 + cos(Float.pi * t))
            }
        }

        return mask
    }

    // MARK: - Dual-Layer Blending
    public func blendDualLayer(
        skyLayer: [Float],
        groundLayer: [Float],
        alphaMask: [Float],
        width: Int,
        height: Int
    ) -> [Float] {
        guard skyLayer.count == width * height,
              groundLayer.count == width * height,
              alphaMask.count == height else {
            return skyLayer
        }

        var blended = [Float](repeating: 0, count: width * height)

        for y in 0..<height {
            let a = alphaMask[y]
            let oneMinusA = 1.0 - a
            let rowOffset = y * width

            for x in 0..<width {
                let idx = rowOffset + x
                blended[idx] = a * skyLayer[idx] + oneMinusA * groundLayer[idx]
            }
        }

        return blended
    }
}
