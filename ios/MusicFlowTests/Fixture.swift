import Foundation
import XCTest

enum Fixture {
    static func data(_ name: String) throws -> Data {
        let bundle = Bundle(for: BundleToken.self)
        guard let url = bundle.url(forResource: name, withExtension: nil) else {
            throw XCTSkip("Missing fixture \(name)") // never happens in CI: the file is in the test bundle
        }
        return try Data(contentsOf: url)
    }

    static func json(_ name: String) throws -> Any {
        try JSONSerialization.jsonObject(with: data(name))
    }
}

private final class BundleToken {}
