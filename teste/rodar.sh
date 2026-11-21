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
# 3b) Brilho na lateral: Ajustes > Brilho, abaixa 3 vezes
adb shell appops set com.geanalves.tvhome SYSTEM_ALERT_WINDOW allow
tecla BACK
adb shell am start -n com.geanalves.tvhome/.MainActivity -e abrir brilho; sleep 3; foto 05c-brilho-100
tecla DPAD_DOWN; tecla DPAD_DOWN; tecla DPAD_DOWN; tecla DPAD_DOWN; foto 05d-brilho-60
tecla DPAD_UP; tecla DPAD_UP; tecla DPAD_UP; tecla BACK
tecla BACK
# 3c) Tela de descanso (hora no centro)
adb shell am start -n com.geanalves.tvhome/.MainActivity -e abrir descanso; sleep 20; foto 05e-descanso
tecla BACK
# 3c2) Modo claro com o céu animado e a ilha de vidro
adb shell am start -n com.geanalves.tvhome/.MainActivity; sleep 6; for i in 1 2 3; do foto 05e2-claro-$i; sleep 1; done
for c in sol chuva noite; do adb shell am start -n com.geanalves.tvhome/.MainActivity -e cena $c; sleep 4; foto 05e3-claro-$c; done
# 3d) Modo escuro (céu azul com estrelas)
adb shell am start -n com.geanalves.tvhome/.MainActivity -e abrir modo; sleep 4; for i in 1 2 3 4 5 6 7 8 9 10 11 12; do foto 05f-escuro-$i; sleep 0.4; done
tecla DPAD_DOWN; sleep 2; foto 05g-escuro-foco
adb shell am start -n com.geanalves.tvhome/.MainActivity -e efeito natal; sleep 3; foto 05h-natal
adb shell am start -n com.geanalves.tvhome/.MainActivity -e efeito nada; sleep 2
# 3e) Papel de parede do TEMPO (tela inicial escura)
for c in sol parcial nublado garoa chuva tempestade neblina amanhecer tarde noite noitenuvem noitechuva; do adb shell am start -n com.geanalves.tvhome/.MainActivity -e cena $c; sleep 4; foto 05l-tempo-$c; done
adb shell am start -n com.geanalves.tvhome/.MainActivity -e cena tempestade; sleep 1; for i in 1 2 3 4 5 6 7 8; do foto 05m-raio-$i; sleep 0.5; done
# 3f) Tela de descanso com o TEMPO
for c in sol chuva tempestade noite tarde neblina; do adb shell am start -n com.geanalves.tvhome/.MainActivity -e cena $c -e abrir descanso; sleep 6; foto 05n-descanso-$c; tecla BACK; sleep 1; done
adb shell am start -n com.geanalves.tvhome/.MainActivity -e cena real; sleep 2
adb shell am start -n com.geanalves.tvhome/.MainActivity -e abrir modo; sleep 8
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
