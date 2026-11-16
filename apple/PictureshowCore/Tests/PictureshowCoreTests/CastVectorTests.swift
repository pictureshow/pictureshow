// SPDX-License-Identifier: MPL-2.0
import Foundation
import XCTest
@testable import PictureshowCore

final class CastVectorTests: XCTestCase {
    func testEveryCastFixtureIndependently() throws {
        var root = URL(fileURLWithPath:#filePath)
        for _ in 0..<5 { root.deleteLastPathComponent() }
        var cases = 0, steps = 0
        for suite in ["cast-capability","cast-session","cast-handoff"] {
            let specRoot = ProcessInfo.processInfo.environment["PICTURESHOW_SPEC_DIR"].map { URL(fileURLWithPath:$0) } ?? root.appendingPathComponent("spec")
            let data = try Data(contentsOf:specRoot.appendingPathComponent("vectors/\(suite).json"))
            let document = try XCTUnwrap(JSONSerialization.jsonObject(with:data) as? [String:Any])
            XCTAssertEqual(document["schemaVersion"] as? Int,1)
            XCTAssertEqual(document["suite"] as? String,suite)
            for c in try XCTUnwrap(document["cases"] as? [[String:Any]]) {
                let id = try XCTUnwrap(c["id"] as? String), op = try XCTUnwrap(c["operation"] as? String)
                do { steps += try CastVectorRunner.run(suite:suite,operation:op,id:id,state:try XCTUnwrap(c["state"] as? [String:Any]),steps:try XCTUnwrap(c["steps"] as? [[String:Any]])) }
                catch { XCTFail("\(id): \(error)") }
                cases += 1
            }
        }
        XCTAssertEqual(cases,189)
        XCTAssertEqual(steps,532)
    }
    func testAdapterKeepsReplacementIntentAndRejectsMalformedCommands() throws {
        let local:[String:Any] = ["server":"s","viewer":"v","device":"phone","session":"p","generation":"1","kind":"local"]
        let next:[String:Any] = ["server":"s","viewer":"v2","device":"phone","session":"p2","generation":"3","kind":"local"]
        let output = try CastHandoff.reduce(state:["owner":["owner":local]],event:["op":"viewer-switch","identity":next,"intent":["entry":"new","ticks":"700","paused":false]])
        let owner = try XCTUnwrap(output["owner"] as? [String:Any]), intent = try XCTUnwrap(owner["intent"] as? [String:Any])
        XCTAssertEqual(intent["entry"] as? String,"new")
        XCTAssertEqual(intent["ticks"] as? String,"700")
        XCTAssertEqual(intent["paused"] as? Bool,false)
        XCTAssertThrowsError(try CastHandoff.reduce(state:["owner":["owner":local],"commands":[:]],event:["op":"progress","ticks":"10"]))
        XCTAssertThrowsError(try CastPolicies.controls(["tracks":[["id":NSNumber(value:Int64.max),"type":"subtitle","label":"x"]]]))
        XCTAssertThrowsError(try CastHandoff.reduce(state:["owner":["owner":local]],event:["op":"observe","observation":["commands":[1]]]))
        XCTAssertEqual(CastMedia.mediaURL("https://media.example/v?api%5Fkey=old&x=1#part",token:"a b"),"https://media.example/v?x=1&ApiKey=a%20b#part")
    }
    func testStrictFailuresDoNotAcceptMissingOrInventedEvidence() throws {
        XCTAssertThrowsError(try CastVectorRunner.execute(suite:"cast-other",operation:"capability",input:[:],context:[:]))
        XCTAssertThrowsError(try CastVectorRunner.execute(suite:"cast-capability",operation:"other",input:[:],context:[:]))
        XCTAssertThrowsError(try CastVectorRunner.execute(suite:"cast-capability",operation:"capability",input:["other":true],context:[:]))
        XCTAssertThrowsError(try CastVectorRunner.execute(suite:"cast-capability",operation:"controls",input:["durationTicks":123],context:[:]))
        XCTAssertThrowsError(try CastVectorRunner.run(suite:"cast-capability",operation:"capability",id:"invented",state:[:],steps:[["event":[:],"expected":["state":"idle"]]]))
        let id = "cast-capability.idle-until-the-chooser-opens"
        XCTAssertThrowsError(try CastVectorRunner.run(suite:"cast-capability",operation:"capability",id:id,state:[:],steps:[]))
        XCTAssertThrowsError(try CastVectorRunner.run(suite:"cast-capability",operation:"capability",id:id,state:[:],steps:[["event":[:],"expected":[:]]]))
        XCTAssertThrowsError(try CastVectorRunner.run(suite:"cast-capability",operation:"capability",id:id,state:[:],steps:[["event":[:],"expected":["state":"ready"]]]))
        XCTAssertThrowsError(try CastVectorRunner.run(suite:"cast-capability",operation:"capability",id:id,state:[:],steps:[["event":[:],"expected":["jellyfinRemote":1]]]))
        XCTAssertFalse(CastVectorRunner.projection([1,2],matches:[1]))
        XCTAssertFalse(CastVectorRunner.projection(["unknown":NSNull()],matches:[:]))
    }
}
