import Foundation

public struct CameraCapabilities: Equatable, Sendable {
    public let cameraId: String
    public let uniqueId: String
    public let supportsRaw: BooleanLiteralType
    public let minExposureSeconds: Double
    public let maxExposureSeconds: Double
    public let minIso: Float
    public let maxIso: Float
    public let sensorWidth: Int
    public let sensorHeight: Int
    public let focalLengthMm: Float
    public let aperture: Float
    public let previewWidth: Int
    public let previewHeight: Int
    public let userLabel: String

    public init(
        cameraId: String,
        uniqueId: String,
        supportsRaw: Bool,
        minExposureSeconds: Double,
        maxExposureSeconds: Double,
        minIso: Float,
        maxIso: Float,
        sensorWidth: Int,
        sensorHeight: Int,
        focalLengthMm: Float,
        aperture: Float,
        previewWidth: Int = 1920,
        previewHeight: Int = 1440,
        userLabel: String = "Main Camera"
    ) {
        self.cameraId = cameraId
        self.uniqueId = uniqueId
        self.supportsRaw = supportsRaw
        self.minExposureSeconds = minExposureSeconds
        self.maxExposureSeconds = maxExposureSeconds
        self.minIso = minIso
        self.maxIso = maxIso
        self.sensorWidth = sensorWidth
        self.sensorHeight = sensorHeight
        self.focalLengthMm = focalLengthMm
        self.aperture = aperture
        self.previewWidth = previewWidth
        self.previewHeight = previewHeight
        self.userLabel = userLabel
    }
}

public struct CaptureSettings: Equatable, Sendable {
    public var exposureDurationSeconds: Double
    public var iso: Float
    public var focusDistance: Float // 0.0 = infinity
    public var autoFocusEnabled: Bool
    public var maxFrames: Int // 0 = unlimited
    public var driftHandling: DriftHandling
    public var stretchType: StretchType
    public var gradientRemovalEnabled: Bool
    public var dualLayerEnabled: Bool

    public init(
        exposureDurationSeconds: Double = 4.0,
        iso: Float = 1600,
        focusDistance: Float = 0.0,
        autoFocusEnabled: Bool = false,
        maxFrames: Int = 0,
        driftHandling: DriftHandling = .crop,
        stretchType: StretchType = .histogram,
        gradientRemovalEnabled: Bool = true,
        dualLayerEnabled: Bool = true
    ) {
        self.exposureDurationSeconds = exposureDurationSeconds
        self.iso = iso
        self.focusDistance = focusDistance
        self.autoFocusEnabled = autoFocusEnabled
        self.maxFrames = maxFrames
        self.driftHandling = driftHandling
        self.stretchType = stretchType
        self.gradientRemovalEnabled = gradientRemovalEnabled
        self.dualLayerEnabled = dualLayerEnabled
    }
}
