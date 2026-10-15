#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Use only a disposable emulator: this script resets this app's test data.
serial="${1:-emulator-5554}"
case "$serial" in emulator-*) ;; *) printf '%s\n' 'Use a disposable emulator serial, not a physical phone.' >&2; exit 1;; esac
adb -s "$serial" get-state >/dev/null
fixture="$(mktemp -d /tmp/loto-android.XXXXXX)"
server_pid=''; web_pid=''
remote_port="$(python3 - <<'PYPORT'
import socket
with socket.socket() as listener:
    listener.bind(('127.0.0.1', 0))
    print(listener.getsockname()[1])
PYPORT
)"
cleanup() {
  for child in "$server_pid" "$web_pid"; do
    if [ -n "$child" ]; then kill "$child" 2>/dev/null || true; wait "$child" 2>/dev/null || true; fi
  done
  rm -f app/src/androidTest/assets/ssh-test.pem
  rm -rf "$fixture"
}
trap cleanup EXIT
ssh-keygen -q -t ed25519 -N '' -f "$fixture/host"
ssh-keygen -q -t rsa -b 2048 -m PEM -N '' -f "$fixture/client"
mkdir -p app/src/androidTest/assets "$fixture/web" capturas
cp "$fixture/client" app/src/androidTest/assets/ssh-test.pem
printf '%s\n' LOTO_ANDROID_TEST > "$fixture/web/index.html"
cat > "$fixture/config" <<CONFIG
ListenAddress 127.0.0.1
Port 22222
HostKey $fixture/host
PidFile $fixture/pid
AuthorizedKeysFile $fixture/client.pub
StrictModes no
UsePAM no
PasswordAuthentication no
KbdInteractiveAuthentication no
AllowTcpForwarding remote
GatewayPorts no
PermitListen 127.0.0.1:$remote_port
ClientAliveInterval 5
ClientAliveCountMax 2
CONFIG
/usr/sbin/sshd -D -e -f "$fixture/config" > "$fixture/ssh.log" 2>&1 &
server_pid=$!
python3 -m http.server 22223 --bind 127.0.0.1 --directory "$fixture/web" > "$fixture/http.log" 2>&1 &
web_pid=$!
sleep 1
for child in "$server_pid" "$web_pid"; do if ! kill -0 "$child" 2>/dev/null; then cat "$fixture/ssh.log" "$fixture/http.log"; exit 1; fi; done
if adb -s "$serial" shell pm path br.com.lotobot.tunnel | rg -q '^package:'; then adb -s "$serial" shell pm clear br.com.lotobot.tunnel >/dev/null; fi
ANDROID_SERIAL="$serial" ./gradlew :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.localSshUser=$(id -un)" "-Pandroid.testInstrumentationRunnerArguments.localSocksPort=$remote_port" --console=plain
adb -s "$serial" pull /sdcard/Download/loto-capturas/. capturas/
