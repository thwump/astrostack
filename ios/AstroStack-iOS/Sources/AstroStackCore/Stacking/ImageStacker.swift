import Foundation

public final class ImageStacker: Sendable {
    public init() {}

    public struct StackResult: Sendable {
        public let stackedR: [Float]
        public let stackedG: [Float]
        public let stackedB: [Float]
        public let totalFrames: Int
        public let width: Int
        public let height: Int

        public init(stackedR: [Float], stackedG: [Float], stackedB: [Float], totalFrames: Int, width: Int, height: Int) {
            self.stackedR = stackedR
            self.stackedG = stackedG
            self.stackedB = stackedB
            self.totalFrames = totalFrames
            self.width = width
            self.height = height
        }
    }

    // MARK: - Running Mean Stacking
    public func accumulateFrame(
        runningSum: inout [Float],
        newFrame: [Float],
        frameCount: Int
    ) {
        guard runningSum.count == newFrame.count else { return }
        for i in 0..<runningSum.count {
            runningSum[i] += newFrame[i]
        }
    }

    public func normalizeStack(
        runningSum: [Float],
        frameCount: Int
    ) -> [Float] {
        guard frameCount > 0 else { return runningSum }
        let inv = 1.0 / Float(frameCount)
        return runningSum.map { $0 * inv }
    }

    // MARK: - Kappa-Sigma Clipping Rejection (Offline / Batch)
    public func kappaSigmaStack(
        frames: [[Float]],
        width: Int,
        height: Int,
        kappa: Float = 2.5
    ) -> [Float] {
        let nFrames = frames.count
        guard nFrames > 0 else { return [] }
        guard nFrames > 2 else {
            // Simple average if fewer than 3 frames
            var avg = [Float](repeating: 0, count: width * height)
            for f in frames {
                for i in 0..<f.count { avg[i] += f[i] }
            }
            let inv = 1.0 / Float(nFrames)
            return avg.map { $0 * inv }
        }

        let numPixels = width * height
        var result = [Float](repeating: 0, count: numPixels)

        for p in 0..<numPixels {
            var vals = [Float]()
            vals.reserveCapacity(nFrames)
            for f in 0..<nFrames {
                vals.append(frames[f][p])
            }

            // Compute mean and std dev
            var sum: Float = 0
            for v in vals { sum += v }
            let mean = sum / Float(nFrames)

            var varSum: Float = 0
            for v in vals {
                let diff = v - mean
                varSum += diff * diff
            }
            let sigma = sqrt(varSum / Float(nFrames))

            let lowBound = mean - kappa * sigma
            let highBound = mean + kappa * sigma

            var acceptedSum: Float = 0
            var acceptedCount: Float = 0
            for v in vals {
                if v >= lowBound && v <= highBound {
                    acceptedSum += v
                    acceptedCount += 1
                }
            }

            result[p] = acceptedCount > 0 ? (acceptedSum / acceptedCount) : mean
        }

        return result
    }
}
