import Foundation
import AstroStackCore

@MainActor
public final class CameraViewModel: ObservableObject {
    @Published public var settings = CaptureSettings()
    @Published public var previewScaleMode: PreviewScaleMode = .fit
    @Published public var isNightVisionMode: Bool = false
    @Published public var isCapturing: Bool = false
    @Published public var capturedCount: Int = 0
    @Published public var stackedCount: Int = 0
    @Published public var rejectedCount: Int = 0
    @Published public var showSettingsSheet: Bool = false
    @Published public var statusMessage: String = "Ready to capture"

    public let cameraManager = AVCameraManager()
    private let starAligner = StarAligner()
    private let horizonDetector = HorizonDetector()
    private let imageStacker = ImageStacker()
    private let histogramStretch = HistogramStretch()
    private let fitsWriter = FitsWriter()

    public init() {
        if let defaultCam = cameraManager.selectedCamera {
            cameraManager.configureSession(for: defaultCam)
        }
    }

    public func selectCamera(_ camera: CameraCapabilities) {
        cameraManager.configureSession(for: camera)
        cameraManager.applyManualSettings(
            exposureSeconds: settings.exposureDurationSeconds,
            iso: settings.iso,
            focusDistance: settings.focusDistance
        )
    }

    public func startCaptureSession() {
        isCapturing = true
        capturedCount = 0
        stackedCount = 0
        rejectedCount = 0
        statusMessage = "Stacking active (Frame #1...)"
    }

    public func stopCaptureSession() {
        isCapturing = false
        statusMessage = "Session completed. Integrated \(stackedCount) frames."
    }

    public func toggleNightVision() {
        isNightVisionMode.toggle()
    }

    public func scoutSingleFrame() {
        statusMessage = "Capturing single scout frame..."
        cameraManager.captureRawFrame()
    }
}
