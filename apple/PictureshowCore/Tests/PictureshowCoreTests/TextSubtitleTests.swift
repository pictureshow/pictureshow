// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import XCTest
@testable import PictureshowCore

final class TextSubtitleTests: XCTestCase {
    func testSRTTimingMarkupAndOverlappingCues() throws {
        let data=Data("\u{feff}1\r\n00:02:00,100 --> 00:02:02,500\r\n<i>Hello</i> &amp; goodbye\r\nSecond line\r\n\r\n2\r\n00:02:01,000 --> 00:02:03,000\r\nOverlap &#x2665;\r\n".utf8)
        let cues=try TextSubtitles.parse(data:data,format:"srt")
        XCTAssertEqual(cues,[TextSubtitleCue(startMs:120_100,endMs:122_500,text:"Hello & goodbye\nSecond line"),TextSubtitleCue(startMs:121_000,endMs:123_000,text:"Overlap ♥")])
        XCTAssertEqual(TextSubtitles.text(cues:cues,positionMs:121_000),"Hello & goodbye\nSecond line\nOverlap ♥")
        XCTAssertEqual(TextSubtitles.text(cues:cues,positionMs:122_500),"Overlap ♥")
        XCTAssertNil(TextSubtitles.text(cues:cues,positionMs:123_000))
    }

    func testWebVTTIdentifiersSettingsAndMetadata() throws {
        let value="""
        WEBVTT Example

        NOTE decorative metadata
        ignored

        STYLE
        ::cue { color: red; }

        REGION
        id:bottom

        first cue
        02:00.000 --> 02:02.000 align:start position:10%
        <v Narrator><c.green>Caption</c></v> &lt;literal&gt;

        01:02:03.000 --> 01:02:04.001
        Long timeline
        """
        let cues=try TextSubtitles.parse(data:Data(value.utf8),format:"webvtt")
        XCTAssertEqual(cues.count,2)
        XCTAssertEqual(cues[0],TextSubtitleCue(startMs:120_000,endMs:122_000,text:"Caption <literal>"))
        XCTAssertEqual(cues[1].startMs,3_723_000)
        XCTAssertEqual(cues[1].endMs,3_724_001)
    }

    func testMalformedInputCannotPublishPartialCaptions() {
        for input in ["1\n00:00:03,000 --> 00:00:02,000\nBackwards","1\n00:60:00,000 --> 00:61:01,000\nInvalid minutes","1\n999999999999999999:00:00,000 --> 999999999999999999:00:01,000\nOverflow","WEBVTT\n\n00:01.000 --> nonsense\nBad","1\n00:00:00,000 --> 00:00:01,000\nGood\n\nBroken block"] {
            XCTAssertThrowsError(try TextSubtitles.parse(data:Data(input.utf8),format:input.hasPrefix("WEBVTT") ? "vtt":"srt"))
        }
        XCTAssertThrowsError(try TextSubtitles.parse(data:Data([0xff]),format:"srt"))
        XCTAssertThrowsError(try TextSubtitles.parse(data:Data(),format:"ass"))
        XCTAssertThrowsError(try TextSubtitles.parse(data:Data(repeating:32,count:TextSubtitles.maximumBytes+1),format:"srt"))
    }

    func testHeaderAndEmptyFileRules() throws {
        XCTAssertEqual(try TextSubtitles.parse(data:Data("WEBVTT\n\n".utf8),format:"vtt"),[])
        XCTAssertThrowsError(try TextSubtitles.parse(data:Data("00:01.000 --> 00:02.000\nMissing header".utf8),format:"vtt"))
        XCTAssertEqual(try TextSubtitles.parse(data:Data(),format:"srt"),[])
    }
}
