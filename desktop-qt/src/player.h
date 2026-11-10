// SPDX-License-Identifier: MPL-2.0
#pragma once
#include <QLibrary>
#include <QJsonObject>
#include <QTimer>
#include <mpv/client.h>
#include <mpv/render.h>
struct Api {
    QLibrary lib;
#define FN(n) decltype(&mpv_##n) n = nullptr
    FN(create); FN(initialize); FN(set_option_string); FN(command); FN(get_property); FN(free); FN(free_node_contents);
    FN(terminate_destroy); FN(wait_event); FN(request_log_messages);
    FN(render_context_create); FN(render_context_render); FN(render_context_free); FN(render_context_set_update_callback); FN(render_context_update); FN(render_context_report_swap);
#undef FN
    explicit Api(const QString &path);
};
class Player : public QObject {
 Q_OBJECT
public:
 Api api;
 mpv_handle *handle = nullptr;
 QTimer events;
 bool loaded = false;
 QString error;
 explicit Player(const QString &lib);
 ~Player();
 void command(QStringList args);
 QJsonValue property(const char *name);
 QJsonObject receipt();
};
