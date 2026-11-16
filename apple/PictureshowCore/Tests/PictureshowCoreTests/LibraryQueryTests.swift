// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import XCTest
@testable import PictureshowCore

final class LibraryQueryTests: XCTestCase {
    private func params(_ query: LibraryQuery) -> [String: String] {
        Dictionary(query.queryItems(startIndex: 100, limit: 100, fields: "F").map { ($0.name, $0.value ?? "") }, uniquingKeysWith: { a, _ in a })
    }

    func testMovieLibraryListsMoviesRecursivelySoFoldersDontLeak() {
        let p = params(LibraryQuery(parentID: "lib", collectionType: "movies"))
        XCTAssertEqual(p["includeItemTypes"], "Movie")
        XCTAssertEqual(p["recursive"], "true")
        XCTAssertEqual(p["startIndex"], "100")
        XCTAssertEqual(p["sortBy"], "SortName")
    }

    func testUnknownLibraryBrowsesDirectChildren() {
        let p = params(LibraryQuery(parentID: "lib", collectionType: nil))
        XCTAssertNil(p["includeItemTypes"])
        XCTAssertNil(p["recursive"])
    }

    func testNonNameSortsBreakTiesByNameAndFiltersAreStable() {
        var q = LibraryQuery(parentID: "lib", collectionType: "tvshows")
        q.sort = .dateAdded; q.descending = true
        q.filters = [.favorites, .unplayed]
        q.genres = ["Drama", "Comedy"]
        let p = params(q)
        XCTAssertEqual(p["sortBy"], "DateCreated,SortName")
        XCTAssertEqual(p["sortOrder"], "Descending")
        XCTAssertEqual(p["filters"], "IsUnplayed,IsFavorite")
        XCTAssertEqual(p["genres"], "Comedy|Drama")
    }

    func testPageSizeIsBounded() {
        let items = LibraryQuery(parentID: "lib", collectionType: "movies").queryItems(startIndex: -5, limit: 10_000, fields: "F")
        XCTAssertEqual(items.first { $0.name == "limit" }?.value, "200")
        XCTAssertEqual(items.first { $0.name == "startIndex" }?.value, "0")
    }
}
