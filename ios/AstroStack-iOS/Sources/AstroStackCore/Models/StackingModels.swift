import Foundation

public struct Star: Equatable, Sendable {
    public let x: Float
    public let y: Float
    public let flux: Float
    public let peak: Float
    public let fwhm: Float

    public init(x: Float, y: Float, flux: Float, peak: Float, fwhm: Float) {
        self.x = x
        self.y = y
        self.flux = flux
        self.peak = peak
        self.fwhm = fwhm
    }
}

public struct TriangleAsterism: Equatable, Sendable {
    public let i1: Int
    public let i2: Int
    public let i3: Int
    public let ratio1: Float
    public let ratio2: Float

    public init(i1: Int, i2: Int, i3: Int, ratio1: Float, ratio2: Float) {
        self.i1 = i1
        self.i2 = i2
        self.i3 = i3
        self.ratio1 = ratio1
        self.ratio2 = ratio2
    }
}

public struct AlignmentTransform: Equatable, Sendable {
    public let dx: Float
    public let dy: Float
    public let angleRad: Float
    public let scale: Float
    public let matchedStars: Int

    public static let identity = AlignmentTransform(dx: 0, dy: 0, angleRad: 0, scale: 1.0, matchedStars: 0)

    public init(dx: Float, dy: Float, angleRad: Float, scale: Float = 1.0, matchedStars: Int) {
        self.dx = dx
        self.dy = dy
        self.angleRad = angleRad
        self.scale = scale
        self.matchedStars = matchedStars
    }

    public func transformPoint(x: Float, y: Float, centerX: Float, centerY: Float) -> (Float, Float) {
        let rx = x - centerX
        let ry = y - centerY
        let cosA = cos(angleRad) * scale
        let sinA = sin(angleRad) * scale
        let nx = rx * cosA - ry * sinA + centerX + dx
        let ny = rx * sinA + ry * cosA + centerY + dy
        return (nx, ny)
    }
}

public enum DriftHandling: String, CaseIterable, Sendable {
    case crop = "CROP"
    case expand = "EXPAND"
    case none = "NONE"

    public var label: String {
        switch self {
        case .crop: return "Crop Uncovered Edges"
        case .expand: return "Expand Canvas (Show All)"
        case .none: return "No Canvas Adjustment"
        }
    }
}

public enum StretchType: String, CaseIterable, Sendable {
    case histogram = "HISTOGRAM"
    case arcsinh = "ARCSINH"

    public var label: String {
        switch self {
        case .histogram: return "Histogram (STF)"
        case .arcsinh: return "Arcsinh (Color Preserving)"
        }
    }
}

public enum PreviewScaleMode: String, CaseIterable, Sendable {
    case fit = "FIT"
    case fill = "FILL"

    public var label: String {
        switch self {
        case .fit: return "Fit (Full FOV)"
        case .fill: return "Fill Screen (Crop)"
        }
    }
}
