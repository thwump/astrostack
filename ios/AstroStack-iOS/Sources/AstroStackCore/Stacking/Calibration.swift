import Foundation

public final class Calibration: Sendable {
    public init() {}

    // MARK: - Dark Frame Calibration
    public func createMasterDark(frames: [[Float]], width: Int, height: Int) -> [Float] {
        guard !frames.isEmpty else { return [] }
        let n = frames.count
        let total = width * height
        var master = [Float](repeating: 0, count: total)

        for p in 0..<total {
            var vals = [Float]()
            for f in 0..<n { vals.append(frames[f][p]) }
            vals.sort()
            master[p] = vals[n / 2] // Median
        }
        return master
    }

    public func subtractDark(light: [Float], dark: [Float]) -> [Float] {
        guard light.count == dark.count else { return light }
        var result = [Float](repeating: 0, count: light.count)
        for i in 0..<light.count {
            result[i] = max(0.0, light[i] - dark[i])
        }
        return result
    }

    // MARK: - Synthetic Flat Vignetting Correction
    public func applySyntheticFlat(
        data: inout [Float],
        width: Int,
        height: Int,
        vignetteFactor: Float = 0.35
    ) {
        guard data.count == width * height else { return }

        let cx = Float(width) * 0.5
        let cy = Float(height) * 0.5
        let maxR = hypot(cx, cy)

        for y in 0..<height {
            let dy = Float(y) - cy
            let rowOffset = y * width

            for x in 0..<width {
                let dx = Float(x) - cx
                let r = hypot(dx, dy) / maxR
                // Quadratic falloff model: V(r) = 1 - k * r^2
                let flatGain = 1.0 / max(0.2, 1.0 - vignetteFactor * (r * r))
                let idx = rowOffset + x
                data[idx] = min(1.0, data[idx] * flatGain)
            }
        }
    }
}
