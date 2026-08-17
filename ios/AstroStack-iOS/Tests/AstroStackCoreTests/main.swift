import Foundation
import AstroStackCore

func assertTest(_ condition: Bool, _ name: String) {
    if condition {
        print("  ✅ [PASS] \(name)")
    } else {
        print("  ❌ [FAIL] \(name)")
        exit(1)
    }
}

print("=======================================================")
print("  Running AstroStack Core Test Suite (Swift Engine)   ")
print("=======================================================")

// MARK: - 1. Star Aligner Tests
print("\n[Suite 1] Star Detection & Triangle Registration:")
let aligner = StarAligner()
let width = 200
let height = 200
var field = [Float](repeating: 0.01, count: width * height)

let starCoords = [(50, 50), (120, 60), (80, 140), (150, 150), (30, 160)]
for (x, y) in starCoords {
    field[y * width + x] = 0.95
    field[(y-1) * width + x] = 0.6
    field[(y+1) * width + x] = 0.6
    field[y * width + x - 1] = 0.6
    field[y * width + x + 1] = 0.6
}

let detected = aligner.detectStars(luminance: field, width: width, height: height)
assertTest(detected.count >= 5, "Detect at least 5 synthetic stars (Found \(detected.count))")

let triangles = aligner.buildAsterisms(stars: detected)
assertTest(triangles.count >= 1, "Build invariant asterism triangles (Built \(triangles.count))")

var shiftedField = [Float](repeating: 0.01, count: width * height)
for (x, y) in starCoords {
    let sx = x + 5
    let sy = y + 3
    shiftedField[sy * width + sx] = 0.95
    shiftedField[(sy-1) * width + sx] = 0.6
    shiftedField[(sy+1) * width + sx] = 0.6
    shiftedField[sy * width + sx - 1] = 0.6
    shiftedField[sy * width + sx + 1] = 0.6
}

let shiftedStars = aligner.detectStars(luminance: shiftedField, width: width, height: height)
let shiftedTriangles = aligner.buildAsterisms(stars: shiftedStars)
let alignment = aligner.computeAlignment(
    refStars: detected,
    tgtStars: shiftedStars,
    refTriangles: triangles,
    tgtTriangles: shiftedTriangles
)

assertTest(alignment != nil, "Compute rigid alignment transform")
if let alignment = alignment {
    assertTest(abs(alignment.dx - 5.0) < 1.5, "Verify estimated dx translation (Est: \(alignment.dx), Expected: 5.0)")
    assertTest(abs(alignment.dy - 3.0) < 1.5, "Verify estimated dy translation (Est: \(alignment.dy), Expected: 3.0)")
}

// MARK: - 2. Horizon Detection Tests
print("\n[Suite 2] Horizon Detection & Dual-Layer Blending:")
let detector = HorizonDetector()
let hWidth = 100
let hHeight = 100
var horizonFrame = [Float](repeating: 0, count: hWidth * hHeight)

for y in 0..<50 {
    for x in 0..<hWidth { horizonFrame[y * hWidth + x] = 0.1 }
}
for y in 50..<100 {
    for x in 0..<hWidth { horizonFrame[y * hWidth + x] = 0.6 }
}

let res = detector.detectHorizon(luminance: horizonFrame, width: hWidth, height: hHeight)
assertTest(res.hasHorizon, "Identify presence of landscape horizon")
assertTest(abs(res.horizonRow - 50) <= 2, "Accurately locate horizon row (Row: \(res.horizonRow), Expected: 50)")

let mask = detector.generateAlphaMask(height: 100, horizonRow: 50, featherRadius: 10)
assertTest(mask[20] == 1.0, "Sky region has alpha 1.0")
assertTest(mask[80] == 0.0, "Ground region has alpha 0.0")
assertTest(abs(mask[50] - 0.5) <= 0.1, "Horizon boundary row has feathered alpha ~0.5")

// MARK: - 3. Histogram & Arcsinh Stretch Tests
print("\n[Suite 3] Statistical MTF STF & Arcsinh Stretches:")
let stretcher = HistogramStretch()
let testData: [Float] = [0.001, 0.005, 0.01, 0.02, 0.05, 0.1, 0.5, 0.9]

let stf = stretcher.applyStfStretch(data: testData)
assertTest(stf.count == testData.count, "STF output array dimensions match")
assertTest(stf[2] > testData[2], "Faint signal midtones lifted by MTF STF")

let arcsinh = stretcher.applyArcsinhStretch(r: testData, g: testData, b: testData)
assertTest(arcsinh.r.count == testData.count, "Arcsinh output array dimensions match")
assertTest(arcsinh.r[3] > testData[3], "Faint color signals above blackpoint lifted by Arcsinh (Raw: \(testData[3]), Stretched: \(arcsinh.r[3]))")

// MARK: - 4. FitsWriter Tests
print("\n[Suite 4] 16-Bit Astronomical FITS Exporter:")
let writer = FitsWriter()
let fWidth = 10
let fHeight = 10
let r = [Float](repeating: 0.25, count: 100)
let g = [Float](repeating: 0.50, count: 100)
let b = [Float](repeating: 0.75, count: 100)

let fitsData = writer.writeFits(
    red: r,
    green: g,
    blue: b,
    width: fWidth,
    height: fHeight,
    exposureSeconds: 4.0,
    iso: 1600,
    frameCount: 10
)

assertTest(!fitsData.isEmpty, "Generate non-empty FITS byte stream")
assertTest(fitsData.count % 2880 == 0, "FITS file block length aligned to 2880-byte multiple (Total bytes: \(fitsData.count))")

let headerStr = String(data: fitsData.prefix(2880), encoding: .ascii) ?? ""
assertTest(headerStr.contains("SIMPLE  =                    T"), "FITS standard SIMPLE header card")
assertTest(headerStr.contains("BITPIX  =                   16"), "16-bit BITPIX header card")
assertTest(headerStr.contains("NAXIS   =                    3"), "3-Axis RGB NAXIS card")
assertTest(headerStr.contains("EXPTIME =                 4.00"), "Exposure duration card")
assertTest(headerStr.contains("STACKNUM=                   10"), "Frame count card")
assertTest(headerStr.contains("END"), "Standard END card present")

print("\n=======================================================")
print("  🎉 ALL ASTROSTACK SWIFT CORE TESTS PASSED (100%)    ")
print("=======================================================\n")
