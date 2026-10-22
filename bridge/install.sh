#!/usr/bin/env bash
# Install / remove the SunnyNav bridge on a comma device.
#
#   ./install.sh              systemd service (survives reboots, recommended)
#   ./install.sh hook         append a start line to launch_chffrplus.sh
#   ./install.sh uninstall    disable and remove everything
#
# Run over SSH from the directory that contains nav_bridge.py.
set -euo pipefail

SERVICE=sunnynav-bridge
DEST=/data/sunnynav
PORT=${PORT:-8765}
PYTHON=${PYTHON:-/usr/local/venv/bin/python}
LAUNCHER=/data/openpilot/launch_chffrplus.sh
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

copy_files() {
  sudo mkdir -p "$DEST"
  sudo install -m 644 "$HERE/nav_bridge.py" "$DEST/nav_bridge.py"
  echo "installed $DEST/nav_bridge.py"
}

install_systemd() {
  copy_files
  sed "s/--port 8765/--port $PORT/" "$HERE/$SERVICE.service" | sudo tee "/etc/systemd/system/$SERVICE.service" >/dev/null
  sudo systemctl daemon-reload
  sudo systemctl enable --now "$SERVICE"
  sleep 2
  systemctl --no-pager --lines=10 status "$SERVICE" || true
  echo
  curl -fsS "http://127.0.0.1:$PORT/health" && echo
}

install_hook() {
  copy_files
  if [ ! -f "$LAUNCHER" ]; then
    echo "no $LAUNCHER, use './install.sh' (systemd) instead" >&2
    exit 1
  fi
  if grep -q 'nav_bridge.py' "$LAUNCHER"; then
    echo "hook already present in $LAUNCHER"
    return
  fi
  # Start the bridge right before the manager, with the launch environment already set up.
  awk -v line="nohup $PYTHON $DEST/nav_bridge.py --port $PORT >> /tmp/nav_bridge.log 2>&1 &" '
    /\.\/manager\.py/ && !done { print line; done = 1 }
    { print }
  ' "$LAUNCHER" > /tmp/launch_chffrplus.new
  sudo cp /tmp/launch_chffrplus.new "$LAUNCHER"
  sudo chmod +x "$LAUNCHER"
  echo "hook added to $LAUNCHER (note: reset by openpilot updates, re-run './install.sh hook')"
}

uninstall() {
  if systemctl list-unit-files | grep -q "^$SERVICE.service"; then
    sudo systemctl disable --now "$SERVICE" || true
    sudo rm -f "/etc/systemd/system/$SERVICE.service"
    sudo systemctl daemon-reload
    echo "removed systemd service"
  fi
  if [ -f "$LAUNCHER" ] && grep -q 'nav_bridge.py' "$LAUNCHER"; then
    sudo grep -v 'nav_bridge.py' "$LAUNCHER" > /tmp/launch_chffrplus.new
    sudo cp /tmp/launch_chffrplus.new "$LAUNCHER"
    sudo chmod +x "$LAUNCHER"
    echo "removed launch hook"
  fi
  sudo pkill -f 'nav_bridge.py' || true
  sudo rm -rf "$DEST"
  echo "uninstalled"
}

case "${1:-systemd}" in
  systemd|install) install_systemd ;;
  hook) install_hook ;;
  uninstall|remove) uninstall ;;
  *) echo "usage: $0 [systemd|hook|uninstall]" >&2; exit 1 ;;
esac
