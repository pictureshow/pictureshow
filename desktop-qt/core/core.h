// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
#pragma once
#include <QJsonObject>
#include <QJsonValue>

namespace pictureshow {
// Independent C++ translations of the similarly named Kotlin production functions.
// Effects are values for an OS adapter; reducers themselves perform no I/O.
QJsonObject reduceMode(QJsonObject state, const QJsonObject &event);
QJsonObject reduceMusicSession(QJsonObject state, const QJsonObject &event);
QJsonObject reduceMusicReport(QJsonObject state, const QJsonObject &event);
int musicBitrateCeiling(const QString &quality, bool metered);
QJsonObject musicDeviceProfile(const QJsonObject &input);
QJsonObject musicOutputFacts(const QJsonObject &input);
}
