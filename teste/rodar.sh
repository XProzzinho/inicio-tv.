#!/bin/bash
# Teste do Início TV numa TV Android virtual: instala, abre, aperta o controle e tira prints.
set -x
mkdir -p prints
adb wait-for-device
adb shell settings put global window_animation_scale 0
adb install -r -g inicio-tv.apk
adb install -r -g lumi-tv.apk || true
adb logcat -c

foto() { adb exec-out screencap -p > "prints/$1.png"; }
tecla() { adb shell input keyevent "$1"; sleep 2; }

# 1) Abre o Início TV
adb shell am start -W -n com.geanalves.tvhome/.MainActivity
sleep 20
foto 01-inicio
# 2) Anda com o controle pela tela
tecla DPAD_DOWN; foto 02-baixo
tecla DPAD_RIGHT; tecla DPAD_RIGHT; foto 03-direita
tecla DPAD_UP; tecla DPAD_UP; foto 04-topo
tecla DPAD_CENTER; sleep 3; foto 05-ok-no-topo
tecla BACK
# 3) Volume (o número na tela)
tecla VOLUME_UP; foto 06-volume
# 4) Tela de todos os apps
adb shell am start -W -n com.geanalves.tvhome/.AppsActivity
sleep 6; foto 07-todos-os-apps
tecla BACK
# 5) Lumi TV aberto pelo Início TV (perfil recebido)
adb shell am start -W -n com.geanalves.tvonline/.IptvActivity || true
sleep 15; foto 08-lumi-tv
tecla HOME; sleep 4; foto 09-home

# Erros: o app travou?
adb logcat -d > prints/logcat.txt
grep -n -A25 "FATAL EXCEPTION" prints/logcat.txt > prints/travou.txt || echo "Nenhum travamento" > prints/travou.txt
adb shell dumpsys activity activities | grep -E "mResumedActivity|topResumedActivity" > prints/tela-atual.txt || true
exit 0
