// SPDX-License-Identifier: MPL-2.0
#pragma once
#include "player.h"
#include <QQuickRhiItem>
#include <QJsonObject>
class Surface: public QQuickRhiItem {
 Q_OBJECT
public:
 static Player *player;
 explicit Surface(QQuickItem *p=nullptr):QQuickRhiItem(p){setColorBufferFormat(TextureFormat::RGBA16F);setAlphaBlending(false);}
 QQuickRhiItemRenderer *createRenderer() override;
};
#ifdef Q_OS_MACOS
void *macCreate(void *device, Player *player);
void macDraw(void *state, void *target, void *commandBuffer, int width, int height);
void macDestroy(void *state);
void macSwap(void *state);
QJsonObject macReceipt(WId view);
void macActivate(WId view);
void macImmersive(WId view,bool enabled);
#endif
