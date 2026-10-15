#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
fixture="$(mktemp -d /tmp/loto-ssh.XXXXXX)"
server_pid=''
remote_port="$(python3 - <<'PYPORT'
import socket
with socket.socket() as listener:
    listener.bind(('127.0.0.1', 0))
    print(listener.getsockname()[1])
PYPORT
)"
cleanup() {
  if [ -n "$server_pid" ]; then kill "$server_pid" 2>/dev/null || true; wait "$server_pid" 2>/dev/null || true; fi
  rm -rf "$fixture"
}
trap cleanup EXIT
ssh-keygen -q -t ed25519 -N '' -f "$fixture/host"
ssh-keygen -q -t rsa -b 2048 -m PEM -N '' -f "$fixture/client"
# A forced command validates the exact recovery request and limits it to this
# disposable fixture port. No sudo privileges or production processes are used.
cat > "$fixture/recover" <<RECOVERY
#!/usr/bin/env bash
set -eu
[ "\$SSH_ORIGINAL_COMMAND" = 'sudo -n fuser -k $remote_port/tcp >/dev/null 2>&1' ] || exit 64
exec "$(command -v fuser)" -k "$remote_port/tcp" >/dev/null 2>&1
RECOVERY
chmod +x "$fixture/recover"
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
ForceCommand $fixture/recover
ClientAliveInterval 5
ClientAliveCountMax 2
CONFIG
/usr/sbin/sshd -D -e -f "$fixture/config" > "$fixture/server.log" 2>&1 &
server_pid=$!
sleep 1
if ! kill -0 "$server_pid" 2>/dev/null; then cat "$fixture/server.log"; exit 1; fi
LOTO_TEST_RECOVERY=1 LOTO_TEST_SOCKS_PORT="$remote_port" LOTO_TEST_SSH_KEY="$fixture/client" ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:architectureCheck --rerun-tasks --console=plain
