import Foundation

public final class StarAligner: Sendable {
    public init() {}

    // MARK: - Star Detection
    public func detectStars(
        luminance: [Float],
        width: Int,
        height: Int,
        maxStars: Int = 100,
        minPeakRatio: Float = 0.05
    ) -> [Star] {
        guard width > 20, height > 20, luminance.count == width * height else { return [] }

        // Approximate 98.5th percentile for adaptive star background cutoff
        var sample = [Float]()
        let step = max(1, luminance.count / 2000)
        for i in stride(from: 0, to: luminance.count, by: step) {
            sample.append(luminance[i])
        }
        sample.sort()
        let pIndex = Int(Double(sample.count) * 0.985)
        let threshold = sample.indices.contains(pIndex) ? max(sample[pIndex], 0.02) : 0.05

        var candidates = [Star]()
        let margin = 8

        for y in margin..<(height - margin) {
            let rowOffset = y * width
            for x in margin..<(width - margin) {
                let val = luminance[rowOffset + x]
                if val < threshold { continue }

                // Check 8-neighbor local peak
                if val > luminance[rowOffset - width + x] &&
                    val > luminance[rowOffset + width + x] &&
                    val > luminance[rowOffset + x - 1] &&
                    val > luminance[rowOffset + x + 1] &&
                    val >= luminance[rowOffset - width + x - 1] &&
                    val >= luminance[rowOffset - width + x + 1] &&
                    val >= luminance[rowOffset + width + x - 1] &&
                    val >= luminance[rowOffset + width + x + 1] {

                    // Refined subpixel centroid via weighted 5x5 box
                    var sumW: Float = 0
                    var sumX: Float = 0
                    var sumY: Float = 0
                    var count: Float = 0

                    for dy in -2...2 {
                        let ny = y + dy
                        let nOffset = ny * width
                        for dx in -2...2 {
                            let nx = x + dx
                            let w = max(0, luminance[nOffset + nx] - threshold * 0.5)
                            sumW += w
                            sumX += Float(nx) * w
                            sumY += Float(ny) * w
                            if w > 0 { count += 1 }
                        }
                    }

                    if sumW > 0 {
                        let cx = sumX / sumW
                        let cy = sumY / sumW
                        let fwhm = sqrt(max(1.0, count))
                        candidates.append(Star(x: cx, y: cy, flux: sumW, peak: val, fwhm: fwhm))
                    }
                }
            }
        }

        // Sort by brightest flux and take top maxStars
        candidates.sort { $0.flux > $1.flux }
        return Array(candidates.prefix(maxStars))
    }

    // MARK: - Asterism Triangles
    public func buildAsterisms(stars: [Star], maxTriangles: Int = 300) -> [TriangleAsterism] {
        guard stars.count >= 3 else { return [] }
        var list = [TriangleAsterism]()
        let n = min(stars.count, 25) // Top 25 brightest stars

        for i in 0..<n {
            for j in (i + 1)..<n {
                for k in (j + 1)..<n {
                    let d1 = hypot(stars[i].x - stars[j].x, stars[i].y - stars[j].y)
                    let d2 = hypot(stars[j].x - stars[k].x, stars[j].y - stars[k].y)
                    let d3 = hypot(stars[k].x - stars[i].x, stars[k].y - stars[i].y)

                    let sides = [d1, d2, d3].sorted()
                    let s1 = sides[0]
                    let s2 = sides[1]
                    let s3 = sides[2]

                    if s1 < 4.0 || s3 > 2000.0 { continue } // Filter degenerate triangles

                    let r1 = s1 / s2
                    let r2 = s2 / s3
                    list.append(TriangleAsterism(i1: i, i2: j, i3: k, ratio1: r1, ratio2: r2))
                    if list.count >= maxTriangles { return list }
                }
            }
        }
        return list
    }

    // MARK: - Alignment Registration
    public func computeAlignment(
        refStars: [Star],
        tgtStars: [Star],
        refTriangles: [TriangleAsterism],
        tgtTriangles: [TriangleAsterism],
        tolerance: Float = 0.018
    ) -> AlignmentTransform? {
        guard refStars.count >= 3, tgtStars.count >= 3 else { return nil }

        var starPairs = [(ref: Star, tgt: Star)]()

        for t1 in refTriangles {
            for t2 in tgtTriangles {
                if abs(t1.ratio1 - t2.ratio1) < tolerance && abs(t1.ratio2 - t2.ratio2) < tolerance {
                    starPairs.append((ref: refStars[t1.i1], tgt: tgtStars[t2.i1]))
                    starPairs.append((ref: refStars[t1.i2], tgt: tgtStars[t2.i2]))
                    starPairs.append((ref: refStars[t1.i3], tgt: tgtStars[t2.i3]))
                }
            }
        }

        guard starPairs.count >= 4 else {
            // Fallback to simple center translation if too few triangle matches
            let dx = tgtStars[0].x - refStars[0].x
            let dy = tgtStars[0].y - refStars[0].y
            return AlignmentTransform(dx: dx, dy: dy, angleRad: 0, scale: 1.0, matchedStars: 1)
        }

        // Estimate rigid transformation (dx, dy, angle)
        var sumDx: Float = 0
        var sumDy: Float = 0
        var count: Float = 0

        for pair in starPairs {
            sumDx += (pair.tgt.x - pair.ref.x)
            sumDy += (pair.tgt.y - pair.ref.y)
            count += 1
        }

        let meanDx = sumDx / count
        let meanDy = sumDy / count

        // Estimate small rotation
        var sumAngle: Float = 0
        var angleCount: Float = 0

        for i in 0..<min(starPairs.count - 1, 30) {
            let p1 = starPairs[i]
            let p2 = starPairs[i + 1]
            let vRefX = p2.ref.x - p1.ref.x
            let vRefY = p2.ref.y - p1.ref.y
            let vTgtX = p2.tgt.x - p1.tgt.x
            let vTgtY = p2.tgt.y - p1.tgt.y

            let magRef = hypot(vRefX, vRefY)
            let magTgt = hypot(vTgtX, vTgtY)
            if magRef > 10 && magTgt > 10 {
                let cross = vRefX * vTgtY - vRefY * vTgtX
                let dot = vRefX * vTgtX + vRefY * vTgtY
                sumAngle += atan2(cross, dot)
                angleCount += 1
            }
        }

        let meanAngle = angleCount > 0 ? (sumAngle / angleCount) : 0.0
        return AlignmentTransform(dx: meanDx, dy: meanDy, angleRad: meanAngle, scale: 1.0, matchedStars: Int(count))
    }

    // MARK: - Bilinear Warp
    public func warpImage(
        source: [Float],
        width: Int,
        height: Int,
        transform: AlignmentTransform
    ) -> [Float] {
        guard source.count == width * height else { return source }
        var output = [Float](repeating: 0, count: width * height)

        let centerX = Float(width) * 0.5
        let centerY = Float(height) * 0.5
        let cosA = cos(-transform.angleRad)
        let sinA = sin(-transform.angleRad)

        for y in 0..<height {
            let rowOffset = y * width
            let fy = Float(y) - centerY - transform.dy

            for x in 0..<width {
                let fx = Float(x) - centerX - transform.dx

                let srcX = fx * cosA - fy * sinA + centerX
                let srcY = fx * sinA + fy * cosA + centerY

                if srcX >= 0 && srcX < Float(width - 1) && srcY >= 0 && srcY < Float(height - 1) {
                    let x0 = Int(srcX)
                    let y0 = Int(srcY)
                    let x1 = x0 + 1
                    let y1 = y0 + 1

                    let wx = srcX - Float(x0)
                    let wy = srcY - Float(y0)

                    let i00 = y0 * width + x0
                    let i10 = y0 * width + x1
                    let i01 = y1 * width + x0
                    let i11 = y1 * width + x1

                    let val = (1 - wx) * (1 - wy) * source[i00] +
                              wx * (1 - wy) * source[i10] +
                              (1 - wx) * wy * source[i01] +
                              wx * wy * source[i11]

                    output[rowOffset + x] = val
                }
            }
        }
        return output
    }
}
