// SPDX-License-Identifier: MPL-2.0
#include "player.h"
#include <QJsonArray>
#include <QDebug>
#include <clocale>
Api::Api(const QString &path):lib(path) {
 if (!lib.load()) qFatal("libmpv load: %s",qPrintable(lib.errorString()));
#define LOAD(n) n=reinterpret_cast<decltype(n)>(lib.resolve("mpv_" #n)); if(!n) qFatal("missing mpv_" #n)
 LOAD(create); LOAD(initialize); LOAD(set_option_string); LOAD(command); LOAD(get_property); LOAD(free); LOAD(free_node_contents); LOAD(terminate_destroy); LOAD(wait_event); LOAD(request_log_messages); LOAD(render_context_create); LOAD(render_context_render); LOAD(render_context_free); LOAD(render_context_set_update_callback); LOAD(render_context_update); LOAD(render_context_report_swap);
#undef LOAD
}
static QJsonValue json(mpv_node &n) {
 switch(n.format) {
 case MPV_FORMAT_STRING:return QString::fromUtf8(n.u.string);
 case MPV_FORMAT_FLAG:return bool(n.u.flag);
 case MPV_FORMAT_INT64:return qint64(n.u.int64);
 case MPV_FORMAT_DOUBLE:return n.u.double_;
 case MPV_FORMAT_NODE_ARRAY:{QJsonArray a;for(int i=0;i<n.u.list->num;i++)a.append(json(n.u.list->values[i]));return a;}
 case MPV_FORMAT_NODE_MAP:{QJsonObject a;for(int i=0;i<n.u.list->num;i++)a[n.u.list->keys[i]]=json(n.u.list->values[i]);return a;}
 default:return {};
 }
}
Player::Player(const QString &lib):api(lib) {
 std::setlocale(LC_NUMERIC,"C"); handle=api.create(); if(!handle)qFatal("mpv_create failed");
 const std::pair<const char*,const char*> opts[]={{"config","no"},{"terminal","no"},{"vo","libmpv"},{"hwdec","auto-safe"},{"mute","yes"},{"ao","null"},{"audio-channels","5.1"},{"keep-open","yes"},{"target-trc","linear"},{"target-prim","bt.709"},{"target-peak","1000"},{"tone-mapping","clip"},{"screenshot-format","jpg"}};
 for(auto [k,v]:opts)if(api.set_option_string(handle,k,v)<0)qFatal("mpv option failed %s=%s",k,v);
 if(api.initialize(handle)<0)qFatal("mpv initialize failed");
 api.request_log_messages(handle,"warn");
 connect(&events,&QTimer::timeout,this,[this]{for(;;){auto *e=api.wait_event(handle,0);if(e->event_id==MPV_EVENT_NONE)break;if(e->event_id==MPV_EVENT_FILE_LOADED)loaded=true;if(e->event_id==MPV_EVENT_END_FILE){auto *d=(mpv_event_end_file*)e->data;if(d->error<0)error=QString("end-file %1").arg(d->error);}if(e->event_id==MPV_EVENT_LOG_MESSAGE){auto *m=(mpv_event_log_message*)e->data;qInfo().noquote()<<QString("mpv %1 %2").arg(m->prefix,m->text).trimmed();}}});events.start(30);
}
Player::~Player(){events.stop();api.terminate_destroy(handle);qInfo()<<"MPV_TEARDOWN_COMPLETE";}
void Player::command(QStringList args){QList<QByteArray> bytes;for(auto &s:args)bytes<<s.toUtf8();QList<const char*> p;for(auto &b:bytes)p<<b.constData();p<<nullptr;int r=api.command(handle,p.data());if(r<0){error=QString("command %1: %2").arg(args.join(' ')).arg(r);qWarning()<<error;}}
QJsonValue Player::property(const char *name){mpv_node n{};if(api.get_property(handle,name,MPV_FORMAT_NODE,&n)<0)return {};auto r=json(n);api.free_node_contents(&n);return r;}
QJsonObject Player::receipt(){QJsonObject r;for(auto p:{"mpv-version","mpv-configuration","ffmpeg-version","video-codec","video-params","video-out-params","audio-params","audio-out-params","hwdec-current","time-pos","frame-drop-count","decoder-frame-drop-count","mistimed-frame-count","vo-delayed-frame-count","estimated-vf-fps","display-fps","track-list","sid"})r[p]=property(p);r["error"]=error;return r;}
