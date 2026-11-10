// SPDX-License-Identifier: MPL-2.0
import QtQuick
import QtQuick.Window
import Pictureshow.Proof
Window {
 id: root
 width: 960; height: 640; visible: true; title: "Pictureshow · Qt surface proof"; color: "black"
 property int clicks: 0
 property string stage: "Local CC0 surface probe"
 Surface { anchors.fill: parent }
 Rectangle {
  objectName: "overlay"; x: parent.width-260; y: 30; width: 230; height: 76; radius: 12; color: "#182e42"
  Text { anchors.centerIn: parent; color: "white"; text: "OVER VIDEO · " + root.clicks; font.pixelSize: 23 }
  MouseArea { anchors.fill: parent; onClicked: root.clicks++ }
 }
 Rectangle {
  anchors.bottom: parent.bottom; width: parent.width; height: 66; color: "#dd152331"
  Row {
   anchors.centerIn: parent; spacing: 16
   Repeater {
    model: ["Pause", "Fullscreen", "Subtitle"]
    Rectangle { required property string modelData; width: 130; height: 44; radius: 8; color: "#496f92"
     Text { anchors.centerIn: parent; text: modelData; color: "white"; font.pixelSize: 17 }
     MouseArea { anchors.fill: parent; onClicked: { if(modelData==="Fullscreen") {proof.fullscreen()} else if(modelData==="Pause") proof.pause();else proof.subtitle(); } }
    }
   }
  }
 }
 Text { x: 22; y: 20; text: root.stage; color: "white"; font.pixelSize: 17 }
}
