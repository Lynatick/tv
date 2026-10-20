#!/bin/zsh
set -euo pipefail
cd "${0:A:h:h}"
export JAVA_HOME='/Users/dmitry_baranov/Library/Java/JavaVirtualMachines/ms-25.0.4.1/Contents/Home'
export ANDROID_HOME='/Users/dmitry_baranov/Library/Android/sdk'
export ANDROID_AVD_HOME="$PWD/.avd"
export PATH="$JAVA_HOME/bin:$PATH"
mkdir -p "$ANDROID_AVD_HOME"
if [[ ! -f "$ANDROID_AVD_HOME/HorizonTV.ini" ]]; then
    print 'no' | "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd --name HorizonTV --package 'system-images;android-34;android-tv;arm64-v8a' --device tv_1080p --path "$ANDROID_AVD_HOME/HorizonTV.avd"
fi
exec "$ANDROID_HOME/emulator/emulator" -avd HorizonTV -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader -memory 2048 -cores 2
