#!/usr/bin/env python3
"""LAN bridge between the SunnyNav phone app and sunnypilot's navigation stack.

Run this on the comma device (same Wi-Fi as the phone):

    cd /data/openpilot
    /usr/local/venv/bin/python nav_bridge.py --port 8765

It exposes:
    GET    /health       -> {"ok": true}
    GET    /status       -> cached GPS / navInstruction / navRoute / NavDestination
    POST   /destination  -> {"latitude": .., "longitude": .., "name": ".."}
                            writes the NavDestination param read by navd
    DELETE /destination  -> removes NavDestination
    POST   /navigation   -> {"enabled": true|false} writes NavigationEnabled

No authentication: only run it on a trusted local network.
"""

import argparse
import json
import os
import sys
import threading
import time
import traceback
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# When started from systemd instead of launch_chffrplus.sh, reproduce the launch
# environment so that openpilot, msgq and the compiled capnp modules are importable.
_ROOT = os.environ.get("OPENPILOT_ROOT", "/data/openpilot")
for _path in (_ROOT, os.path.join(_ROOT, "msgq_repo"), os.path.join(_ROOT, "opendbc_repo"), "/data/pythonpath"):
  if os.path.isdir(_path) and _path not in sys.path:
    sys.path.insert(0, _path)

from openpilot.common.params import Params
import openpilot.cereal.messaging as messaging


class State:
  def __init__(self) -> None:
    self.lock = threading.Lock()
    self.params = Params()
    self.gps = None  # (latitude, longitude, bearing, monotonic_time)
    self.instruction = None  # dict
    self.instruction_at = 0.0
    self.route = []  # [(longitude, latitude)]
    self.route_at = 0.0

  def set_gps(self, latitude: float, longitude: float, bearing: float, now: float) -> None:
    with self.lock:
      self.gps = (latitude, longitude, bearing, now)

  def set_instruction(self, msg, valid: bool, now: float) -> None:
    with self.lock:
      self.instruction = {
        "valid": valid,
        "distanceToManeuver": float(msg.maneuverDistance),
        "primaryText": str(msg.maneuverPrimaryText),
        "secondaryText": str(msg.maneuverSecondaryText),
        "type": str(msg.maneuverType),
        "modifier": str(msg.maneuverModifier),
        "distanceRemaining": float(msg.distanceRemaining),
        "timeRemaining": float(msg.timeRemaining),
      }
      self.instruction_at = now

  def set_route(self, coordinates, now: float) -> None:
    with self.lock:
      self.route = coordinates
      self.route_at = now

  def snapshot(self) -> dict:
    now = time.monotonic()
    with self.lock:
      gps = None
      if self.gps is not None:
        latitude, longitude, bearing, at = self.gps
        gps = {
          "latitude": latitude,
          "longitude": longitude,
          "bearing": bearing,
          "age": round(now - at, 2),
        }
      instruction = None
      if self.instruction is not None and now - self.instruction_at < 5.0:
        instruction = dict(self.instruction)
      route = [list(pair) for pair in self.route] if now - self.route_at < 30.0 else []
      destination = self.params.get("NavDestination")
      if isinstance(destination, (bytes, bytearray)):
        destination = destination.decode("utf-8", "replace")
      try:
        navigation_enabled = bool(self.params.get_bool("NavigationEnabled"))
      except Exception:
        navigation_enabled = False
    return {
      "ok": True,
      "navigationEnabled": navigation_enabled,
      "gps": gps,
      "instruction": instruction,
      "route": route,
      "destination": destination,
    }

  def set_destination(self, latitude: float, longitude: float) -> None:
    # JSON param consumed by navd: destination['longitude'], destination['latitude']
    self.params.put("NavDestination", {"latitude": latitude, "longitude": longitude})
    # navd and mapsd are only started while this is enabled.
    self.params.put_bool("NavigationEnabled", True)

  def clear_destination(self) -> None:
    self.params.remove("NavDestination")


def reader(state: State, stop: threading.Event) -> None:
  sm = None
  while not stop.is_set():
    if sm is None:
      try:
        sm = messaging.SubMaster(["gpsLocation", "gpsLocationExternal", "navInstruction", "navRoute"])
      except Exception:
        # openpilot may not be running yet (or the capnp modules are still building)
        traceback.print_exc()
        stop.wait(2.0)
        continue
    try:
      sm.update(200)
      now = time.monotonic()

      for service in ("gpsLocationExternal", "gpsLocation"):
        if sm.updated[service]:
          gps = sm[service]
          if gps.hasFix:
            state.set_gps(gps.latitude, gps.longitude, float(getattr(gps, "bearingDeg", 0.0)), now)

      if sm.updated["navInstruction"]:
        state.set_instruction(sm["navInstruction"], bool(sm.valid["navInstruction"]), now)

      if sm.updated["navRoute"]:
        coordinates = [(float(c.longitude), float(c.latitude)) for c in sm["navRoute"].coordinates]
        state.set_route(coordinates, now)
    except Exception:
      traceback.print_exc()
      stop.wait(1.0)


class Handler(BaseHTTPRequestHandler):
  state = None  # type: State
  server_version = "SunnyNavBridge/1.0"

  def _send(self, code: int, payload: dict) -> None:
    body = json.dumps(payload).encode("utf-8")
    self.send_response(code)
    self.send_header("Content-Type", "application/json; charset=utf-8")
    self.send_header("Content-Length", str(len(body)))
    self.send_header("Access-Control-Allow-Origin", "*")
    self.end_headers()
    self.wfile.write(body)

  def _read_json(self):
    length = int(self.headers.get("Content-Length", "0") or "0")
    if length <= 0:
      return {}
    return json.loads(self.rfile.read(length).decode("utf-8"))

  def do_OPTIONS(self) -> None:  # noqa: N802
    self.send_response(204)
    self.send_header("Access-Control-Allow-Origin", "*")
    self.send_header("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
    self.send_header("Access-Control-Allow-Headers", "Content-Type")
    self.end_headers()

  def do_GET(self) -> None:  # noqa: N802
    path = self.path.split("?", 1)[0]
    if path == "/health":
      self._send(200, {"ok": True, "service": "sunnypilot-nav-bridge"})
    elif path == "/status":
      self._send(200, self.state.snapshot())
    else:
      self._send(404, {"ok": False, "error": "not found"})

  def do_POST(self) -> None:  # noqa: N802
    path = self.path.split("?", 1)[0]
    try:
      payload = self._read_json()
      if path == "/destination":
        latitude = float(payload["latitude"])
        longitude = float(payload["longitude"])
        if not (-90.0 <= latitude <= 90.0 and -180.0 <= longitude <= 180.0):
          raise ValueError("invalid coordinates")
        self.state.set_destination(latitude, longitude)
        self._send(200, {"ok": True})
      elif path == "/navigation":
        self.state.params.put_bool("NavigationEnabled", bool(payload.get("enabled", True)))
        self._send(200, {"ok": True})
      else:
        self._send(404, {"ok": False, "error": "not found"})
    except Exception as exc:
      self._send(400, {"ok": False, "error": str(exc)})

  def do_DELETE(self) -> None:  # noqa: N802
    path = self.path.split("?", 1)[0]
    if path == "/destination":
      self.state.clear_destination()
      self._send(200, {"ok": True})
    else:
      self._send(404, {"ok": False, "error": "not found"})

  def log_message(self, fmt: str, *args) -> None:
    print("[nav_bridge] " + fmt % args, flush=True)


def main() -> None:
  parser = argparse.ArgumentParser(description="SunnyNav <-> sunnypilot LAN bridge")
  parser.add_argument("--host", default="0.0.0.0")
  parser.add_argument("--port", type=int, default=8765)
  args = parser.parse_args()

  state = State()
  stop = threading.Event()
  thread = threading.Thread(target=reader, args=(state, stop), daemon=True)
  thread.start()

  Handler.state = state
  server = ThreadingHTTPServer((args.host, args.port), Handler)
  print(f"[nav_bridge] listening on http://{args.host}:{args.port}", flush=True)
  try:
    server.serve_forever()
  except KeyboardInterrupt:
    pass
  finally:
    stop.set()
    server.server_close()


if __name__ == "__main__":
  main()
