import Foundation

public final class HistogramStretch: Sendable {
    public init() {}

    // MARK: - MTF (Midtone Transfer Function)
    public func mtf(val: Float, midtone: Float) -> Float {
        guard val > 0 else { return 0 }
        guard val < 1 else { return 1 }
        let denom = (2.0 * midtone - 1.0) * val - midtone
        if abs(denom) < 1e-6 { return val }
        return ((midtone - 1.0) * val) / denom
    }

    // MARK: - Statistical STF (Screen Transfer Function)
    public func applyStfStretch(
        data: [Float],
        targetBackground: Float = 0.25,
        shadowsClipping: Float = -2.8
    ) -> [Float] {
        guard !data.isEmpty else { return data }

        // Compute median and median absolute deviation (MAD)
        var sample = [Float]()
        let step = max(1, data.count / 4000)
        for i in stride(from: 0, to: data.count, by: step) { sample.append(data[i]) }
        sample.sort()

        let med = sample[sample.count / 2]
        var diffs = sample.map { abs($0 - med) }
        diffs.sort()
        let mad = diffs[diffs.count / 2]

        let c = max(0.0, med + shadowsClipping * 1.4826 * mad)
        let delta = med - c
        let midtone = min(0.99, max(0.01, mtf(val: delta, midtone: targetBackground)))

        var output = [Float](repeating: 0, count: data.count)
        for i in 0..<data.count {
            let normalized = max(0.0, (data[i] - c) / max(1e-5, 1.0 - c))
            output[i] = mtf(val: normalized, midtone: midtone)
        }
        return output
    }

    // MARK: - Arcsinh Color-Preserving Stretch
    public func applyArcsinhStretch(
        r: [Float],
        g: [Float],
        b: [Float],
        stretchFactor: Float = 100.0,
        blackPoint: Float = 0.005
    ) -> (r: [Float], g: [Float], b: [Float]) {
        guard r.count == g.count, g.count == b.count, !r.isEmpty else {
            return (r, g, b)
        }

        var outR = [Float](repeating: 0, count: r.count)
        var outG = [Float](repeating: 0, count: g.count)
        var outB = [Float](repeating: 0, count: b.count)

        let asinhFactor = asinh(stretchFactor)

        for i in 0..<r.count {
            let subR = max(0, r[i] - blackPoint)
            let subG = max(0, g[i] - blackPoint)
            let subB = max(0, b[i] - blackPoint)

            let lum = 0.2126 * subR + 0.7152 * subG + 0.0722 * subB
            if lum <= 1e-6 {
                outR[i] = 0
                outG[i] = 0
                outB[i] = 0
                continue
            }

            let stretchedLum = asinh(lum * stretchFactor) / asinhFactor
            let factor = stretchedLum / lum

            outR[i] = min(1.0, subR * factor)
            outG[i] = min(1.0, subG * factor)
            outB[i] = min(1.0, subB * factor)
        }

        return (outR, outG, outB)
    }

    // MARK: - Background Sky Neutralization
    public func neutralizeBackground(
        r: inout [Float],
        g: inout [Float],
        b: inout [Float]
    ) {
        guard !r.isEmpty, r.count == g.count, g.count == b.count else { return }

        // Find median of each channel
        var rSample = [Float](), gSample = [Float](), bSample = [Float]()
        let step = max(1, r.count / 2000)
        for i in stride(from: 0, to: r.count, by: step) {
            rSample.append(r[i])
            gSample.append(g[i])
            bSample.append(b[i])
        }
        rSample.sort(); gSample.sort(); bSample.sort()

        let medR = rSample[rSample.count / 2]
        let medG = gSample[gSample.count / 2]
        let medB = bSample[bSample.count / 2]

        let targetMed = (medR + medG + medB) / 3.0

        let scaleR = medR > 1e-5 ? (targetMed / medR) : 1.0
        let scaleG = medG > 1e-5 ? (targetMed / medG) : 1.0
        let scaleB = medB > 1e-5 ? (targetMed / medB) : 1.0

        for i in 0..<r.count {
            r[i] = min(1.0, r[i] * scaleR)
            g[i] = min(1.0, g[i] * scaleG)
            b[i] = min(1.0, b[i] * scaleB)
        }
    }
}
