// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
#include "core.h"
#include <QCoreApplication>
#include <QFile>
#include <QFileInfo>
#include <QJsonArray>
#include <QJsonDocument>
#include <QSet>
#include <QTextStream>
#include <stdexcept>

namespace {
[[noreturn]] void fail(const QString &message) { throw std::runtime_error(message.toStdString()); }
QJsonValue required(const QJsonObject &o, const QString &key) {
    if (!o.contains(key)) fail("Missing required field: " + key);
    return o.value(key);
}
QString encode(const QJsonValue &value) {
    return QString::fromUtf8(QJsonDocument(QJsonArray{value}).toJson(QJsonDocument::Compact));
}
// The state-trace fixtures deliberately assert explicit projections, just as
// ControlVectorsTest and MusicVectorsTest do. Scalars/null/arrays are exact.
// Stateless sample outputs compare the entire value, including all object keys.
void projection(const QJsonValue &expected, const QJsonValue &actual, const QString &path) {
    if (expected.isObject()) {
        if (!actual.isObject()) fail(path + ": expected object, got " + encode(actual));
        const auto e = expected.toObject();
        const auto a = actual.toObject();
        for (auto i = e.begin(); i != e.end(); ++i) {
            if (!a.contains(i.key())) fail(path + ": missing field " + i.key());
            projection(i.value(), a.value(i.key()), path + "." + i.key());
        }
    } else if (expected != actual) fail(path + ": expected " + encode(expected) + ", got " + encode(actual));
}
QJsonValue once(const QString &suite, const QString &op, const QJsonObject &input) {
    if (suite != "music-quality") fail("Unexpected stateless suite: " + suite);
    if (op == "ceiling") return pictureshow::musicBitrateCeiling(input.value("quality").toString(), input.value("metered").toBool());
    if (op == "profile") {
        const auto profile = pictureshow::musicDeviceProfile(input);
        QJsonArray direct, conversion;
        for (const auto &row : profile.value("DirectPlayProfiles").toArray()) {
            const auto p = row.toObject();
            direct.append(p.value("Container").toString() + ":" + p.value("AudioCodec").toString());
        }
        for (const auto &row : profile.value("TranscodingProfiles").toArray()) {
            const auto p = row.toObject();
            const auto protocol = p.value("Protocol").toString() == "hls" ? "Hls" : "Http";
            conversion.append(p.value("Container").toString() + ":" + p.value("AudioCodec").toString()
                              + ":" + protocol + ":" + p.value("VideoCodec").toString());
        }
        return QJsonObject{{"MaxStreamingBitrate", profile.value("MaxStreamingBitrate")},
                           {"MaxStaticMusicBitrate", profile.value("MaxStaticMusicBitrate")},
                           {"MusicStreamingTranscodingBitrate", profile.value("MusicStreamingTranscodingBitrate")},
                           {"direct", direct}, {"conversion", conversion}};
    }
    if (op == "output") return pictureshow::musicOutputFacts(input);
    fail("Unknown music-quality operation: " + op);
}
QJsonObject step(const QString &suite, const QString &op, const QJsonObject &state, const QJsonObject &event) {
    if (suite == "playback-mode" && op == "mode") return pictureshow::reduceMode(state, event);
    if (suite == "music-session" && op == "session") return pictureshow::reduceMusicSession(state, event);
    if (suite == "music-reports" && op == "report") return pictureshow::reduceMusicReport(state, event);
    fail("Unknown suite/operation: " + suite + "/" + op);
}
}

int main(int argc, char **argv) {
    QCoreApplication app(argc, argv);
    QTextStream out(stdout), err(stderr);
    const QSet<QString> supported{"playback-mode", "music-session", "music-reports", "music-quality"};
    QSet<QString> ids, suites;
    QJsonArray results;
    int cases = 0, assertions = 0, failures = 0;
    if (argc < 2) {
        err << "Usage: pictureshow_vectors FILE.json [FILE.json ...]\n"
               "This proof implements four suites only; unknown suites fail.\n";
        return 2;
    }
    for (int argument = 1; argument < argc; ++argument) {
        const auto filename = QString::fromLocal8Bit(argv[argument]);
        int fileCases = 0, fileAssertions = 0, fileFailures = 0;
        QString suite;
        try {
            QFile file(filename);
            if (!file.open(QIODevice::ReadOnly)) fail("Cannot open vector file: " + QFileInfo(filename).fileName());
            QJsonParseError parse;
            const auto document = QJsonDocument::fromJson(file.readAll(), &parse);
            if (parse.error != QJsonParseError::NoError || !document.isObject()) fail("Invalid JSON document");
            const auto root = document.object();
            if (required(root, "schemaVersion") != QJsonValue(1)) fail("Unsupported schema version");
            suite = required(root, "suite").toString();
            if (!supported.contains(suite)) fail("Unsupported suite: " + suite);
            if (suites.contains(suite)) fail("Duplicate suite: " + suite);
            suites.insert(suite);
            const auto rows = required(root, "cases");
            if (!rows.isArray() || rows.toArray().isEmpty()) fail("Empty or malformed suite");
            for (const auto &value : rows.toArray()) {
                QString id;
                ++cases; ++fileCases;
                try {
                    if (!value.isObject()) fail("Case must be an object");
                    const auto c = value.toObject();
                    id = required(c, "id").toString();
                    if (id.isEmpty() || ids.contains(id)) fail("Empty or duplicate case ID: " + id);
                    ids.insert(id);
                    const auto op = required(c, "operation").toString();
                    if (c.contains("steps")) {
                        const auto trace = c.value("steps");
                        if (!trace.isArray() || trace.toArray().isEmpty()) fail("Empty or malformed trace");
                        auto state = required(c, "state").toObject();
                        int index = 0;
                        for (const auto &row : trace.toArray()) {
                            const auto entry = row.toObject();
                            state = step(suite, op, state, required(entry, "event").toObject());
                            const auto expected = required(entry, "expected");
                            if (!expected.isObject() || expected.toObject().isEmpty()) fail("Trace step has no assertions");
                            ++assertions; ++fileAssertions;
                            projection(expected, state, id + "/" + QString::number(index++));
                        }
                    } else {
                        const auto actual = once(suite, op, required(c, "input").toObject());
                        const auto expected = required(c, "expected");
                        ++assertions; ++fileAssertions;
                        if (actual != expected) fail(id + ": expected " + encode(expected) + ", got " + encode(actual));
                    }
                } catch (const std::exception &e) {
                    ++failures; ++fileFailures;
                    err << QFileInfo(filename).fileName() << "/" << id << ": " << e.what() << '\n';
                }
            }
        } catch (const std::exception &e) {
            ++failures; ++fileFailures;
            err << QFileInfo(filename).fileName() << ": " << e.what() << '\n';
        }
        results.append(QJsonObject{{"suite", suite}, {"file", QFileInfo(filename).fileName()},
                                  {"cases", fileCases}, {"checks", fileAssertions}, {"failures", fileFailures}});
    }
    out << QJsonDocument(QJsonObject{{"suites", results}, {"cases", cases}, {"checks", assertions}, {"failures", failures}}).toJson(QJsonDocument::Indented);
    return failures == 0 ? 0 : 1;
}
