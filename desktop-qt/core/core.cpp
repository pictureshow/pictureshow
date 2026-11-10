// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
#include "core.h"
#include <QJsonArray>
#include <QStringList>
#include <algorithm>
#include <limits>
#include <stdexcept>

namespace {
void defaults(QJsonObject &object, const QJsonObject &values) {
    for (auto i = values.begin(); i != values.end(); ++i)
        if (!object.contains(i.key())) object.insert(i.key(), i.value());
}
QString text(const QJsonObject &o, const QString &key) { return o.value(key).toString(); }
qint64 decimal(const QJsonObject &o, const QString &key, qint64 fallback = 0) {
    if (!o.contains(key)) return fallback;
    const auto value = o.value(key);
    bool ok = false;
    auto number = value.toString().toLongLong(&ok);
    if (!value.isString() || !ok) throw std::invalid_argument("Expected decimal int64 string");
    return number;
}
void unknown(const QString &op) {
    throw std::invalid_argument(("Unknown operation: " + op).toStdString());
}
QStringList strings(const QJsonValue &value) {
    QStringList result;
    for (const auto &item : value.toArray()) result.append(item.toString());
    return result;
}
}

namespace pictureshow {
QJsonObject reduceMode(QJsonObject s, const QJsonObject &event) {
    defaults(s, {{"ownerKind", "local"}, {"media", "video"}, {"explicitAudio", false},
                 {"group", QJsonValue::Null}, {"requestedRoute", QJsonValue::Null}, {"leaving", false}});
    s["effect"] = "none";
    const auto op = text(event, "op");
    const bool group = !s.value("group").isNull();
    if (op == "background") {
        s["effect"] = text(s, "ownerKind") != "local" ? "keep-receiver"
            : text(s, "media") == "audio" && s.value("explicitAudio").toBool() ? "keep-audio" : "pause-video";
    } else if (op == "transport") {
        s["effect"] = group ? "group-request" : "owner-command";
    } else if (op == "route") {
        const auto route = text(event, "route");
        if (route != "cast" && route != "airplay") throw std::invalid_argument("Unknown route");
        s["requestedRoute"] = route;
        s["leaving"] = false;
        s["effect"] = group ? "require-leave" : "select-route";
    } else if (op == "cancel") {
        s["requestedRoute"] = QJsonValue::Null;
        s["leaving"] = false;
    } else if (op == "leave") {
        if (!s.value("requestedRoute").isNull() && group) {
            s["leaving"] = true;
            s["effect"] = "leave-group";
        }
    } else if (op == "left") {
        if (s.value("leaving").toBool() && !s.value("requestedRoute").isNull()) {
            s["group"] = QJsonValue::Null;
            s["leaving"] = false;
            s["effect"] = "select-route";
        }
    } else unknown(op);
    return s;
}

QJsonObject reduceMusicSession(QJsonObject s, const QJsonObject &event) {
    defaults(s, {{"intent", "stopped"}, {"interrupted", false}, {"ducked", false}, {"keys", QJsonArray()}});
    s["effect"] = "none";
    const auto op = text(event, "op");
    const auto intent = text(s, "intent");
    const bool active = intent == "play" || intent == "pause";
    auto stop = [&](const QString &effect) {
        s["intent"] = "stopped";
        s["interrupted"] = false;
        s["ducked"] = false;
        s["effect"] = effect;
    };
    if (op == "key") {
        auto keys = s.value("keys").toArray();
        if (event.value("key").isNull() || !event.contains("key") || keys.contains(event.value("key"))) {
            s["effect"] = "ignore";
        } else {
            keys.append(event.value("key"));
            while (keys.size() > 8) keys.removeFirst();
            s["keys"] = keys;
            s["effect"] = "accept";
        }
    } else if (op == "viewer-play") {
        if (intent != "play" || s.value("interrupted").toBool()) {
            s["intent"] = "play"; s["interrupted"] = false; s["effect"] = "play";
        }
    } else if (op == "viewer-pause") {
        if (active && intent != "pause") { s["intent"] = "pause"; s["effect"] = "pause"; }
    } else if (op == "interrupted") {
        if (intent == "play" && !s.value("interrupted").toBool()) {
            s["interrupted"] = true; s["effect"] = "pause";
        }
    } else if (op == "interruption-ended") {
        if (s.value("interrupted").toBool()) {
            s["interrupted"] = false;
            if (intent == "play") s["effect"] = "play";
        }
    } else if (op == "focus-lost" || op == "noisy") {
        if (intent == "play") { s["intent"] = "pause"; s["interrupted"] = false; s["effect"] = "pause"; }
    } else if (op == "duck" || op == "unduck") {
        s["ducked"] = op == "duck";
    } else if (op == "route-connected") {
        // A newly available route alone does not start audio.
    } else if (op == "video-start" || op == "stop") {
        if (active) stop("stop-keep");
    } else if (op == "queue-clear" || op == "sign-out" || op == "revoked") {
        stop("stop-clear");
    } else unknown(op);
    return s;
}

QJsonObject reduceMusicReport(QJsonObject s, const QJsonObject &event) {
    defaults(s, {{"viewing", QJsonValue::Null}, {"serial", "0"}});
    s["effects"] = QJsonArray();
    const auto viewing = s.value("viewing").toObject();
    const bool started = viewing.value("started").toBool();
    const auto op = text(event, "op");
    const auto ticks = decimal(event, "ticks");
    auto effect = [&](const QString &report, qint64 at, bool paused, bool failed) {
        return QJsonObject{{"report", report}, {"media", viewing.value("media")}, {"serial", viewing.value("serial")},
                           {"ticks", QString::number(at)}, {"paused", paused}, {"failed", failed}};
    };
    auto close = [&](bool failed) {
        if (!started) return QJsonArray();
        const auto duration = decimal(event, "durationTicks");
        const auto at = event.value("natural").toBool() && duration > 0 ? duration : ticks;
        return QJsonArray{effect("stopped", at, false, failed)};
    };
    if (op == "load") {
        if (!event.value("entry").isString() || !event.value("media").isString()) return s;
        if (viewing.value("entry") == event.value("entry") && !started) return s;
        const auto serial = decimal(s, "serial");
        if (serial == std::numeric_limits<qint64>::max()) throw std::overflow_error("Viewing serial exhausted");
        s["serial"] = QString::number(serial + 1);
        s["viewing"] = QJsonObject{{"entry", event.value("entry")}, {"media", event.value("media")},
                                   {"serial", QString::number(serial + 1)}, {"started", false}};
        s["effects"] = close(false);
    } else if (op == "playing") {
        if (!viewing.isEmpty()) {
            if (!started) {
                auto next = viewing; next["started"] = true; s["viewing"] = next;
                s["effects"] = QJsonArray{effect("start", ticks, false, false)};
            } else s["effects"] = QJsonArray{effect("progress", ticks, event.value("paused").toBool(), false)};
        }
    } else if (op == "progress") {
        if (started) s["effects"] = QJsonArray{effect("progress", ticks, event.value("paused").toBool(), false)};
    } else if (op == "ended" || op == "stop") {
        s["viewing"] = QJsonValue::Null;
        s["effects"] = close(event.value("failed").toBool());
    } else unknown(op);
    return s;
}

int musicBitrateCeiling(const QString &quality, bool metered) {
    if (quality == "original") return 320000000;
    if (quality == "256") return 256000;
    if (quality == "128") return 128000;
    return metered ? 256000 : 320000000;
}

QJsonObject musicDeviceProfile(const QJsonObject &input) {
    const auto decoders = strings(input.value("decoders"));
    const int ceiling = input.value("ceiling").toInt();
    // Kotlin AudioCodec enum order is the stable wire codec order, independent of capability input order.
    const QStringList codecOrder{"AAC", "AC3", "EAC3", "TRUEHD", "DTS", "FLAC", "ALAC", "OPUS", "VORBIS", "MP3", "MP2", "PCM"};
    const QList<QPair<QString, QStringList>> containers{
        {"flac", {"FLAC"}}, {"mp3", {"MP3"}}, {"aac", {"AAC"}},
        {"m4a,m4b,mp4,mov", {"AAC", "ALAC", "FLAC", "MP3", "OPUS"}},
        {"ogg,oga", {"VORBIS", "OPUS", "FLAC"}}, {"opus", {"OPUS"}},
        {"webm,webma", {"OPUS", "VORBIS"}}, {"wav", {"PCM"}},
        {"mka,mkv", {"AAC", "ALAC", "FLAC", "MP3", "OPUS", "VORBIS", "PCM"}}};
    QJsonArray direct;
    for (const auto &container : containers) {
        QStringList supported;
        for (const auto &codec : codecOrder) {
            if (!decoders.contains(codec) || !container.second.contains(codec)) continue;
            if (codec == "PCM") supported.append({"pcm_s16le", "pcm_s16be", "pcm_s24le", "pcm_s24be", "pcm_s32le", "pcm_f32le", "pcm_u8"});
            else supported.append(codec.toLower());
        }
        if (!supported.isEmpty()) direct.append(QJsonObject{{"Container", container.first}, {"AudioCodec", supported.join(',')},
                                                           {"VideoCodec", QJsonValue::Null}, {"Type", "Audio"}});
    }
    QJsonArray conversion;
    if (decoders.contains("AAC")) conversion.append(QJsonObject{
        {"Container", "ts"}, {"Type", "Audio"}, {"VideoCodec", ""}, {"AudioCodec", "aac"}, {"Protocol", "hls"},
        {"EstimateContentLength", false}, {"EnableMpegtsM2TsMode", false}, {"TranscodeSeekInfo", "Auto"},
        {"CopyTimestamps", false}, {"Context", "Streaming"}, {"EnableSubtitlesInManifest", false},
        {"MaxAudioChannels", "2"}, {"MinSegments", 0}, {"SegmentLength", 0}, {"BreakOnNonKeyFrames", true},
        {"Conditions", QJsonArray()}, {"EnableAudioVbrEncoding", true}});
    return {{"Name", "Pictureshow music"}, {"MaxStreamingBitrate", ceiling}, {"MaxStaticBitrate", ceiling},
            {"MaxStaticMusicBitrate", ceiling}, {"MusicStreamingTranscodingBitrate", std::min(ceiling, 256000)},
            {"DirectPlayProfiles", direct}, {"TranscodingProfiles", conversion}, {"ContainerProfiles", QJsonArray()},
            {"CodecProfiles", QJsonArray()}, {"SubtitleProfiles", QJsonArray()}};
}

QJsonObject musicOutputFacts(const QJsonObject &input) {
    const auto source = input.value("source").toObject();
    QJsonObject stream;
    for (const auto &row : source.value("MediaStreams").toArray()) {
        const auto candidate = row.toObject();
        if (text(candidate, "Type") == "Audio") { stream = candidate; break; }
    }
    const auto rawCodec = text(stream, "Codec").toLower();
    auto codec = text(stream, "Codec").toUpper();
    if (codec.trimmed().isEmpty()) codec = text(source, "Container").toUpper();
    const auto route = text(input, "route");
    const bool converted = text(input, "method") == "Transcode";
    const bool sourceLossless = QStringList{"flac", "alac", "ape", "wavpack", "tak", "truehd", "mlp"}.contains(rawCodec) || rawCodec.startsWith("pcm_");
    const bool lossless = !converted && sourceLossless && !QStringList{"bluetooth", "cast", "remote"}.contains(route);
    QString label;
    if (converted) label = "Converted to AAC, up to " + QString::number(std::min(input.value("ceiling").toInt(), 256000) / 1000) + " kbps (lossy)";
    else {
        label = codec.trimmed().isEmpty() ? "Original file" : codec;
        label += lossless ? " (lossless)" : sourceLossless ? route == "bluetooth" ? " sent over Bluetooth (lossy)" : " sent to another device" : " (original)";
    }
    QJsonValue reason(QJsonValue::Null);
    if (converted) {
        const auto reasons = strings(input.value("reasons"));
        bool bitrate = false;
        for (const auto &item : reasons) if (item.contains("Bitrate")) bitrate = true;
        if (bitrate) reason = "It's above the Music quality you picked. Change Music quality in Settings to hear the original.";
        else {
            const auto name = codec.trimmed().isEmpty() ? QString("this file") : codec;
            QString why = "This device can't decode " + name;
            QString copy = sourceLossless && rawCodec != "flac" ? "a FLAC copy" : "an AAC copy";
            const QList<QStringList> shapes{{"AudioChannelsNotSupported", "channel count", "a stereo copy"},
                                           {"AudioSampleRateNotSupported", "sample rate", "a 48 kHz copy"},
                                           {"AudioBitDepthNotSupported", "bit depth", "a 16-bit copy"}};
            for (const auto &shape : shapes) if (reasons.contains(shape[0])) {
                why = "This device can't play " + name + " at its " + shape[1]; copy = shape[2]; break;
            }
            reason = why + ", so the server converts it. It still plays, and " + copy + " on the server plays directly.";
        }
    }
    return {{"label", label}, {"lossless", lossless}, {"converted", converted}, {"reason", reason}};
}
}
