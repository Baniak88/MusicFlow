import XCTest

/// Drives the real app in the simulator (live network) and attaches screenshots of the main
/// screens to the test result, so the UI can be reviewed without an iPhone.
final class ScreenshotTests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments += ["-uitest-fresh", "-AppleLanguages", "(pl)", "-AppleLocale", "pl_PL"]
        app.launch()
    }

    private func shot(_ name: String) {
        let a = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        a.name = name
        a.lifetime = .keepAlways
        add(a)
    }

    /// Result rows of the visible screen (other tabs stay alive in the background).
    private func rows(containing text: String) -> XCUIElementQuery {
        app.buttons.matching(NSPredicate(format: "identifier == 'track.row' AND label CONTAINS[c] %@", text))
    }

    private func clear(_ field: XCUIElement) {
        field.tap()
        let text = (field.value as? String) ?? ""
        field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: text.count + 2))
    }

    func testMainFlow() throws {
        XCTAssertTrue(app.buttons["tab.home"].waitForExistence(timeout: 10))
        sleep(4) // radio shelf loads
        shot("01-home")

        app.buttons["tab.search"].tap()
        let field = app.textFields["search.field"]
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap()
        field.typeText("dawid podsiadło\n")
        let row = rows(containing: "Podsiad").firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 25), "YouTube Music results")
        sleep(2)
        shot("02-search-youtube")

        row.tap()
        XCTAssertTrue(app.buttons["miniplayer"].waitForExistence(timeout: 10))
        sleep(6) // stream starts
        shot("03-mini-player")
        app.buttons["miniplayer"].tap()
        XCTAssertTrue(app.buttons["player.close"].waitForExistence(timeout: 5))
        sleep(2)
        shot("04-player")

        app.buttons["player.queue"].tap()
        sleep(2)
        shot("05-queue")
        app.swipeDown(velocity: .fast)
        sleep(1)
        if app.buttons["player.close"].exists { app.buttons["player.close"].tap() }

        app.buttons["search.source.radio"].tap()
        clear(field)
        field.typeText("radio fama kielce\n")
        XCTAssertTrue(rows(containing: "FAMA").firstMatch.waitForExistence(timeout: 25), "radio results")
        sleep(2)
        shot("06-search-radio")

        app.buttons["search.source.musicbrainz"].tap()
        clear(field)
        field.typeText("karma police radiohead\n")
        XCTAssertTrue(rows(containing: "Radiohead").firstMatch.waitForExistence(timeout: 30), "MusicBrainz results")
        sleep(3)
        shot("07-search-musicbrainz")

        app.buttons["tab.library"].tap()
        sleep(1)
        shot("08-library")

        app.buttons["tab.home"].tap()
        sleep(2)
        shot("09-home-after")
    }
}
