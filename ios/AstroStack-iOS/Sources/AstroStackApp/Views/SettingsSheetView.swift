import SwiftUI
import AstroStackCore

public struct SettingsSheetView: View {
    @ObservedObject var viewModel: CameraViewModel
    @Environment(\.dismiss) private var dismiss

    public init(viewModel: CameraViewModel) {
        self.viewModel = viewModel
    }

    public var body: some View {
        NavigationStack {
            Form {
                // MARK: - Exposure & Sensor
                Section(header: Label("Exposure & Sensor", systemImage: "timer")) {
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Text("Shutter Speed")
                            Spacer()
                            Text(String(format: "%.1fs", viewModel.settings.exposureDurationSeconds))
                                .bold()
                        }
                        Slider(
                            value: $viewModel.settings.exposureDurationSeconds,
                            in: 0.5...30.0,
                            step: 0.5
                        )
                    }

                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Text("ISO Sensitivity")
                            Spacer()
                            Text("ISO \(Int(viewModel.settings.iso))")
                                .bold()
                        }
                        Slider(
                            value: $viewModel.settings.iso,
                            in: 100...6400,
                            step: 100
                        )
                    }

                    Toggle("Auto Focus", isOn: $viewModel.settings.autoFocusEnabled)
                }

                // MARK: - Live Stacking & Alignment
                Section(header: Label("Stacking & Dual-Layer", systemImage: "sparkles")) {
                    Picker("Stretch Type", selection: $viewModel.settings.stretchType) {
                        ForEach(StretchType.allCases, id: \.self) { type in
                            Text(type.label).tag(type)
                        }
                    }

                    Picker("Drift Mode", selection: $viewModel.settings.driftHandling) {
                        ForEach(DriftHandling.allCases, id: \.self) { mode in
                            Text(mode.label).tag(mode)
                        }
                    }

                    Toggle("Dual-Layer Landscape Stacking", isOn: $viewModel.settings.dualLayerEnabled)
                    Toggle("Sky Gradient Neutralization", isOn: $viewModel.settings.gradientRemovalEnabled)
                }

                // MARK: - Viewfinder Options
                Section(header: Label("Viewfinder & Display", systemImage: "camera.viewfinder")) {
                    Picker("Aspect Ratio Mode", selection: $viewModel.previewScaleMode) {
                        ForEach(PreviewScaleMode.allCases, id: \.self) { mode in
                            Text(mode.label).tag(mode)
                        }
                    }

                    Toggle("Red Night-Vision Mode", isOn: $viewModel.isNightVisionMode)
                }
            }
            .navigationTitle("Astro Settings")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") {
                        dismiss()
                    }
                }
            }
        }
    }
}
