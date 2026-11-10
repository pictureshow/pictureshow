// SPDX-License-Identifier: MPL-2.0
#include "surface.h"
#include <QGuiApplication>
#include <QElapsedTimer>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include <QQuickWindow>
#include <QScreen>
#include <QDir>
#include <QFile>
#include <QJsonDocument>
#include <QJsonArray>
#include <QTest>
#include <QMutex>
#include <QMutexLocker>
#include <rhi/qrhi.h>
class Proof:public QObject {
 Q_OBJECT
 Player *p;
public:
 explicit Proof(Player *player):p(player){}
 Q_INVOKABLE void pause(){p->command({"cycle","pause"});}
 Q_INVOKABLE void subtitle(){p->command({"cycle","sid"});}
};
static QJsonArray rect(QRect r){return {r.x(),r.y(),r.width(),r.height()};}
int main(int argc,char**argv){
 QGuiApplication app(argc,argv);auto args=app.arguments();if(args.size()<4)qFatal("usage: qt-proof LIBMPV FIXTURE_DIR EVIDENCE_DIR [--sustain]");
 QString fixtures=QDir(args[2]).absolutePath(),out=QDir(args[3]).absolutePath();QDir().mkpath(out);
 qmlRegisterType<Surface>("Pictureshow.Proof",1,0,"Surface");
 Player player(args[1]);Surface::player=&player;Proof proof(&player);
 QQmlApplicationEngine engine;engine.rootContext()->setContextProperty("proof",&proof);engine.load(QUrl("qrc:/Main.qml"));
 if(engine.rootObjects().isEmpty())return 2;auto*w=qobject_cast<QQuickWindow*>(engine.rootObjects().first());w->requestActivate();
#ifdef Q_OS_MACOS
 macActivate(w->winId());
#endif
 QJsonObject graphics;QMutex graphicsMutex;
 QObject::connect(w,&QQuickWindow::afterRendering,w,[&]{QJsonObject g;if(auto *sc=w->swapChain()){g["swapchainFormat"]=int(sc->format());g["swapchainPixels"]=QJsonArray{sc->currentPixelSize().width(),sc->currentPixelSize().height()};auto h=sc->hdrInfo();g["hdrLuminanceBehavior"]=int(h.luminanceBehavior);g["hdrLimitsType"]=int(h.limitsType);if(h.limitsType==QRhiSwapChainHdrInfo::ColorComponentValue){g["hdrMaxColorComponent"]=h.limits.colorComponentValue.maxColorComponentValue;g["hdrPotentialMaxColorComponent"]=h.limits.colorComponentValue.maxPotentialColorComponentValue;}else{g["hdrMaxNits"]=h.limits.luminanceInNits.maxLuminance;g["hdrMinNits"]=h.limits.luminanceInNits.minLuminance;}}QMutexLocker lock(&graphicsMutex);graphics=g;},Qt::DirectConnection);
 bool failed=false;QElapsedTimer elapsed;elapsed.start();
 auto capture=[&](QString name){
  auto r=player.receipt();r["stage"]=name;r["elapsedMs"]=elapsed.elapsed();r["qtVersion"]=qVersion();r["windowGeometry"]=rect(w->geometry());r["frameGeometry"]=rect(w->frameGeometry());r["screenGeometry"]=rect(w->screen()->geometry());r["devicePixelRatio"]=w->devicePixelRatio();r["qtVisibility"]=int(w->visibility());r["clicks"]=w->property("clicks").toInt();
  {QMutexLocker lock(&graphicsMutex);for(auto it=graphics.begin();it!=graphics.end();++it)r[it.key()]=it.value();}
#ifdef Q_OS_MACOS
  r["native"]=macReceipt(w->winId());
#endif
  // Use native display capture for evidence; Qt6.12 grabWindow misinterprets FP16 readback on macOS.
  auto screen=w->screen()->grabWindow(0);
  if(!screen.isNull()) {
   auto image=screen.toImage();auto sg=w->screen()->geometry();qreal scale=qreal(image.width())/sg.width();
   QRect content=w->geometry().translated(-sg.topLeft());QRect px(qRound(content.x()*scale),qRound(content.y()*scale),qRound(content.width()*scale),qRound(content.height()*scale));
   image.copy(px).scaled(800,800,Qt::KeepAspectRatio,Qt::SmoothTransformation).save(out+"/"+name+".jpg","JPG",50);
   image.scaled(800,800,Qt::KeepAspectRatio,Qt::SmoothTransformation).save(out+"/"+name+"-display.jpg","JPG",50);r["captureMethod"]="native whole-display and geometry crop";
  } else {auto image=w->grabWindow();r["captureMethod"]="Qt grabWindow fallback; HDR capture requires independent review";r["captureImageFormat"]=int(image.format());if(image.isNull())failed=true;else image.convertToFormat(QImage::Format_RGB32).scaled(800,800,Qt::KeepAspectRatio,Qt::SmoothTransformation).save(out+"/"+name+".jpg","JPG",50);}
  QFile f(out+"/"+name+".json");f.open(QFile::WriteOnly);f.write(QJsonDocument(r).toJson());
  if(!player.error.isEmpty())failed=true;qInfo()<<"CAPTURE"<<name;
 };
 using Step=std::pair<int,std::function<void()>>;QList<Step> steps;int at=0;
 auto add=[&](int wait,std::function<void()> fn){at+=wait;steps.append({at,fn});};
 add(1500,[&]{player.command({"loadfile",fixtures+"/hevc-main.mkv"});});
 add(6500,[&]{capture("01-main-window");QTest::mouseClick(w,Qt::LeftButton,Qt::NoModifier,QPoint(w->width()-145,68));});
 add(1000,[&]{capture("02-overlay-input");if(w->property("clicks").toInt()!=1)failed=true;w->resize(740,500);});
 add(1500,[&]{capture("03-resize");w->showFullScreen();});
 add(3500,[&]{capture("04-fullscreen");if(w->geometry()!=w->screen()->geometry())failed=true;w->showNormal();});
 add(3000,[&]{capture("05-return");player.command({"loadfile",fixtures+"/hevc-main10.mkv"});});
 add(6000,[&]{capture("06-main10");player.command({"set","pause","yes"});player.command({"seek","1","absolute+exact"});player.command({"set","sid","1"});});
 add(1200,[&]{capture("07-srt");player.command({"set","sid","2"});});
 add(1200,[&]{capture("08-ass");player.command({"set","sid","3"});});
 add(1200,[&]{capture("09-pgs");player.command({"loadfile",fixtures+"/av1.mkv"});player.command({"set","pause","no"});});
 add(6500,[&]{capture("10-av1");player.command({"loadfile",fixtures+"/hevc-pq-metadata.mkv"});});
 add(6500,[&]{capture("11-pq-input");});
 double sustainStart=-1; qint64 sustainWallStart=0;
 if(args.contains("--sustain")){
  add(1000,[&]{player.command({"loadfile",fixtures+"/sustain-1080p60.mkv"});});
  add(6000,[&]{sustainStart=player.property("time-pos").toDouble(-1);sustainWallStart=elapsed.elapsed();capture("12-sustain-start");});
  add(121000,[&]{capture("13-sustain-end");double end=player.property("time-pos").toDouble(-1);auto vp=player.property("video-params").toObject();bool valid=sustainStart>=0 && end-sustainStart>=119 && vp["w"].toInt()==1920 && vp["h"].toInt()==1080;QFile f(out+"/sustain-verdict.json");f.open(QFile::WriteOnly);f.write(QJsonDocument(QJsonObject{{"mediaSeconds",end-sustainStart},{"wallMs",elapsed.elapsed()-sustainWallStart},{"qualifiedProgress",valid}}).toJson());if(!valid)failed=true;});
 }
 add(1000,[&]{capture("14-before-teardown");w->hide();engine.clearComponentCache();w->releaseResources();});
 add(1000,[&]{engine.rootObjects().first()->deleteLater();});
 add(1000,[&]{QFile f(out+"/sequence.json");f.open(QFile::WriteOnly);f.write(QJsonDocument(QJsonObject{{"status",failed?"FAIL":"HARNESS_COMPLETED_VISUAL_REVIEW_REQUIRED"},{"elapsedMs",elapsed.elapsed()}}).toJson());app.exit(failed?2:0);});
 for(auto &s:steps)QTimer::singleShot(s.first,&app,s.second);
 int result=app.exec();qInfo()<<"QT_EVENT_LOOP_COMPLETE"<<result;return result;
}
#include "main.moc"
