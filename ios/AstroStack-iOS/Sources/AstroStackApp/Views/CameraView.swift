import SwiftUI
import AstroStackCore

public struct CameraView: View {
    @StateObject private var viewModel = CameraViewModel()

    public init() {}

    public var body: some View {
        ZStack {
            // Viewfinder Background
            Color.black.ignoresSafeArea()

            VStack(spacing: 0) {
                // Top Bar
                HStack {
                    Button(action: { viewModel.toggleNightVision() }) {
                        Image(systemName: viewModel.isNightVisionMode ? "eye.fill" : "eye")
                            .font(.title3)
                            .foregroundColor(viewModel.isNightVisionMode ? .red : .white)
                            .padding(8)
                            .background(Color.black.opacity(0.6))
                            .clipShape(Circle())
                    }

                    Spacer()

                    // Quick status info
                    Text(viewModel.statusMessage)
                        .font(.caption)
                        .foregroundColor(.white.opacity(0.8))
                        .padding(.horizontal, 12)
                        .padding(.vertical, 4)
                        .background(Color.black.opacity(0.6))
                        .cornerRadius(12)

                    Spacer()

                    Button(action: { viewModel.showSettingsSheet = true }) {
                        Image(systemName: "gearshape.fill")
                            .font(.title3)
                            .foregroundColor(viewModel.isNightVisionMode ? .red : .white)
                            .padding(8)
                            .background(Color.black.opacity(0.6))
                            .clipShape(Circle())
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)

                Spacer()

                // Center Viewport Simulation
                Rectangle()
                    .fill(Color.black)
                    .aspectRatio(3.0 / 4.0, contentMode: .fit)
                    .overlay(
                        VStack {
                            Image(systemName: "sparkles")
                                .font(.system(size: 48))
                                .foregroundColor(viewModel.isNightVisionMode ? .red.opacity(0.3) : .white.opacity(0.3))
                            Text("Astro Viewfinder")
                                .font(.headline)
                                .foregroundColor(viewModel.isNightVisionMode ? .red.opacity(0.6) : .white.opacity(0.6))
                        }
                    )

                Spacer()

                // Floating Lens Switcher (0.5x, 1x, 5x)
                HStack(spacing: 12) {
                    ForEach(viewModel.cameraManager.availableCameras, id: \.uniqueId) { cam in
                        let isSelected = viewModel.cameraManager.selectedCamera?.uniqueId == cam.uniqueId
                        Button(action: { viewModel.selectCamera(cam) }) {
                            Text(cam.userLabel.prefix(4))
                                .font(.subheadline)
                                .bold()
                                .padding(.horizontal, 14)
                                .padding(.vertical, 8)
                                .background(isSelected ? (viewModel.isNightVisionMode ? Color.red : Color.white) : Color.black.opacity(0.6))
                                .foregroundColor(isSelected ? Color.black : (viewModel.isNightVisionMode ? Color.red : Color.white))
                                .clipShape(Capsule())
                        }
                    }
                }
                .padding(.bottom, 12)

                // Bottom Control Bar
                HStack(spacing: 40) {
                    // Scout Single Frame
                    Button(action: { viewModel.scoutSingleFrame() }) {
                        VStack(spacing: 4) {
                            Image(systemName: "scope")
                                .font(.title2)
                            Text("Scout")
                                .font(.caption2)
                        }
                        .foregroundColor(viewModel.isNightVisionMode ? .red : .white)
                    }

                    // Main Stacking Shutter Button
                    Button(action: {
                        if viewModel.isCapturing {
                            viewModel.stopCaptureSession()
                        } else {
                            viewModel.startCaptureSession()
                        }
                    }) {
                        ZStack {
                            Circle()
                                .stroke(viewModel.isNightVisionMode ? Color.red : Color.white, lineWidth: 4)
                                .frame(width: 72, height: 72)

                            if viewModel.isCapturing {
                                RoundedRectangle(cornerRadius: 6)
                                    .fill(viewModel.isNightVisionMode ? Color.red : Color.red)
                                    .frame(width: 28, height: 28)
                            } else {
                                Circle()
                                    .fill(viewModel.isNightVisionMode ? Color.red : Color.white)
                                    .frame(width: 58, height: 58)
                            }
                        }
                    }

                    // Gallery / Stacks
                    Button(action: {}) {
                        VStack(spacing: 4) {
                            Image(systemName: "photo.stack")
                                .font(.title2)
                            Text("Stacks")
                                .font(.caption2)
                        }
                        .foregroundColor(viewModel.isNightVisionMode ? .red : .white)
                    }
                }
                .padding(.bottom, 24)
            }
        }
        .sheet(isPresented: $viewModel.showSettingsSheet) {
            SettingsSheetView(viewModel: viewModel)
        }
        .preferredColorScheme(.dark)
        .tint(viewModel.isNightVisionMode ? .red : .blue)
    }
}
