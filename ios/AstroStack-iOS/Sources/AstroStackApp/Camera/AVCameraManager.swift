import Foundation
@preconcurrency import AVFoundation
import AstroStackCore

#if canImport(UIKit)
import UIKit
#endif

@MainActor
public final class AVCameraManager: NSObject, ObservableObject, Sendable {
    @Published public private(set) var availableCameras: [CameraCapabilities] = []
    @Published public private(set) var selectedCamera: CameraCapabilities?
    @Published public private(set) var isSessionRunning: Bool = false
    @Published public private(set) var lastCapturedRaw: [Float]?

    private let captureSession = AVCaptureSession()
    private let photoOutput = AVCapturePhotoOutput()
    private var activeDevice: AVCaptureDevice?
    private var activeDeviceInput: AVCaptureDeviceInput?

    public override init() {
        super.init()
        discoverCameras()
    }

    // MARK: - Camera Discovery
    public func discoverCameras() {
        #if os(iOS)
        let deviceTypes: [AVCaptureDevice.DeviceType] = [
            .builtInWideAngleCamera,
            .builtInUltraWideCamera,
            .builtInTelephotoCamera
        ]
        #else
        let deviceTypes: [AVCaptureDevice.DeviceType] = [
            .builtInWideAngleCamera
        ]
        #endif

        let discoverySession = AVCaptureDevice.DiscoverySession(
            deviceTypes: deviceTypes,
            mediaType: .video,
            position: .back
        )

        var list = [CameraCapabilities]()
        for device in discoverySession.devices {
            let label: String
            let minExp: Double
            let maxExp: Double
            let minIso: Float
            let maxIso: Float
            let sensorW: Int
            let sensorH: Int

            #if os(iOS)
            switch device.deviceType {
            case .builtInUltraWideCamera: label = "0.5x Ultra-Wide"
            case .builtInWideAngleCamera: label = "1.0x Wide (Main)"
            case .builtInTelephotoCamera: label = "5.0x Telephoto"
            default: label = device.localizedName
            }
            let formats = device.formats
            let maxFormat = formats.max { $0.highResolutionStillImageDimensions.width < $1.highResolutionStillImageDimensions.width }
            let dims = maxFormat?.highResolutionStillImageDimensions ?? CMVideoDimensions(width: 4032, height: 3024)
            sensorW = Int(dims.width)
            sensorH = Int(dims.height)
            minExp = device.activeFormat.minExposureDuration.seconds
            maxExp = device.activeFormat.maxExposureDuration.seconds
            minIso = device.activeFormat.minISO
            maxIso = device.activeFormat.maxISO
            #else
            label = device.localizedName
            sensorW = 4032
            sensorH = 3024
            minExp = 0.001
            maxExp = 30.0
            minIso = 100.0
            maxIso = 6400.0
            #endif

            let cap = CameraCapabilities(
                cameraId: device.uniqueID,
                uniqueId: device.uniqueID,
                supportsRaw: true,
                minExposureSeconds: minExp,
                maxExposureSeconds: maxExp,
                minIso: minIso,
                maxIso: maxIso,
                sensorWidth: sensorW,
                sensorHeight: sensorH,
                focalLengthMm: 4.0,
                aperture: 1.8,
                previewWidth: 1920,
                previewHeight: 1440,
                userLabel: label
            )
            list.append(cap)
        }

        self.availableCameras = list
        self.selectedCamera = list.first { $0.userLabel.contains("1.0x") } ?? list.first
    }

    // MARK: - Setup & Configure Session
    public func configureSession(for camera: CameraCapabilities) {
        captureSession.beginConfiguration()
        captureSession.sessionPreset = .photo

        if let currentInput = activeDeviceInput {
            captureSession.removeInput(currentInput)
        }

        guard let device = AVCaptureDevice(uniqueID: camera.uniqueId),
              let input = try? AVCaptureDeviceInput(device: device) else {
            captureSession.commitConfiguration()
            return
        }

        if captureSession.canAddInput(input) {
            captureSession.addInput(input)
            self.activeDevice = device
            self.activeDeviceInput = input
        }

        if !captureSession.outputs.contains(photoOutput) && captureSession.canAddOutput(photoOutput) {
            captureSession.addOutput(photoOutput)
            #if os(iOS)
            photoOutput.isHighResolutionCaptureEnabled = true
            #endif
        }

        captureSession.commitConfiguration()
        self.selectedCamera = camera
    }

    // MARK: - Manual Controls
    public func applyManualSettings(exposureSeconds: Double, iso: Float, focusDistance: Float) {
        guard let device = activeDevice else { return }
        do {
            try device.lockForConfiguration()

            #if os(iOS)
            // Manual Exposure on iOS / iPadOS
            let duration = CMTime(seconds: exposureSeconds, preferredTimescale: 1_000_000)
            let clampedDuration = max(device.activeFormat.minExposureDuration, min(device.activeFormat.maxExposureDuration, duration))
            let clampedIso = max(device.activeFormat.minISO, min(device.activeFormat.maxISO, iso))
            device.setExposureModeCustom(duration: clampedDuration, iso: clampedIso, completionHandler: nil)

            // Manual Focus (0.0 = infinity)
            if device.isFocusModeSupported(.locked) {
                device.setFocusModeLocked(lensPosition: focusDistance, completionHandler: nil)
            }

            // Disable continuous OIS drift for tripod astrophotography
            if device.isSmoothAutoFocusSupported {
                device.isSmoothAutoFocusEnabled = false
            }
            #endif

            device.unlockForConfiguration()
        } catch {
            print("Failed to lock device configuration: \(error)")
        }
    }

    public func startSession() {
        guard !captureSession.isRunning else { return }
        let session = self.captureSession
        DispatchQueue.global(qos: .userInitiated).async {
            session.startRunning()
            Task { @MainActor in
                self.isSessionRunning = true
            }
        }
    }

    public func stopSession() {
        guard captureSession.isRunning else { return }
        let session = self.captureSession
        DispatchQueue.global(qos: .userInitiated).async {
            session.stopRunning()
            Task { @MainActor in
                self.isSessionRunning = false
            }
        }
    }

    // MARK: - Capture RAW Frame
    public func captureRawFrame() {
        #if os(iOS)
        guard let rawFormat = photoOutput.availableRawPhotoPixelFormatTypes.first else { return }
        let settings = AVCapturePhotoSettings(rawPixelFormatType: rawFormat)
        photoOutput.capturePhoto(with: settings, delegate: self)
        #else
        let settings = AVCapturePhotoSettings()
        photoOutput.capturePhoto(with: settings, delegate: self)
        #endif
    }
}

extension AVCameraManager: AVCapturePhotoCaptureDelegate {
    public nonisolated func photoOutput(
        _ output: AVCapturePhotoOutput,
        didFinishProcessingPhoto photo: AVCapturePhoto,
        error: Error?
    ) {
        guard error == nil, let fileData = photo.fileDataRepresentation() else { return }
        print("Captured RAW frame of size: \(fileData.count) bytes")
    }
}
