import SwiftUI

@main
struct MusicFlowApp: App {
    @StateObject private var settings: AppSettings
    @StateObject private var library: LibraryStore
    @StateObject private var player: PlayerModel
    @StateObject private var router = Router()

    init() {
        let settings = AppSettings()
        var libraryURL = LibraryStore.defaultURL
        if ProcessInfo.processInfo.arguments.contains("-uitest-fresh") {
            // UI tests start from an empty library that is not the user's.
            libraryURL = FileManager.default.temporaryDirectory.appendingPathComponent("uitest-library.json")
            try? FileManager.default.removeItem(at: libraryURL)
        }
        let library = LibraryStore(fileURL: libraryURL)
        _settings = StateObject(wrappedValue: settings)
        _library = StateObject(wrappedValue: library)
        _player = StateObject(wrappedValue: PlayerModel(resolver: Services.resolver, library: library))
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(settings)
                .environmentObject(library)
                .environmentObject(player)
                .environmentObject(router)
        }
    }
}
