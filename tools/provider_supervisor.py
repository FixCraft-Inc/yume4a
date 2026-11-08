"""Independent, fail-closed owner of one fixture daemon. No credential output."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import select
import signal
import socket
import subprocess
import sys
import time

sys.dont_write_bytecode = True


def send(channel: socket.socket, value: dict) -> None:
    channel.sendall(json.dumps(value, separators=(",", ":")).encode() + b"\n")


def supervise(channel: socket.socket, binary: Path, config: Path, log: Path) -> None:
    """Own only this Popen process group, including resolver descendants."""
    daemon: subprocess.Popen | None = None
    wall_deadline = float("inf")
    mono_deadline = float("inf")
    last_wall = time.time()
    buffer = b""

    def running() -> bool:
        # Observe without reaping: keep the group leader's PID reserved until stop.
        return daemon is not None and os.waitid(os.P_PID, daemon.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT) is None

    def stop() -> None:
        nonlocal daemon
        if daemon is not None:
            # The child is not reaped before signalling, so its PID cannot be reused.
            try:
                os.killpg(daemon.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            daemon.wait(timeout=5)
            daemon = None

    def due() -> bool:
        nonlocal last_wall
        wall = time.time()
        rollback = wall + 0.1 < last_wall
        last_wall = max(last_wall, wall)
        return rollback or wall >= wall_deadline - 0.25 or time.monotonic() >= mono_deadline - 0.25

    try:
        while True:
            if due():
                stop()
            ready, _, _ = select.select([channel], [], [], 0.02)
            if not ready:
                continue
            chunk = channel.recv(16384)
            if not chunk:
                return
            buffer += chunk
            if len(buffer) > 16384:
                return
            while b"\n" in buffer:
                line, buffer = buffer.split(b"\n", 1)
                message = json.loads(line)
                operation = message["op"]
                response: dict = {"ok": True}
                if operation == "arm":
                    # Replacements are accepted only from the serialized journal owner.
                    remaining = message["remaining"]
                    if remaining is None:
                        wall_deadline = mono_deadline = float("inf")
                    else:
                        wall_deadline = float(message["expires_at"])
                        mono_deadline = time.monotonic() + max(0.0, float(remaining))
                    if due():
                        stop()
                elif operation == "start":
                    if daemon is not None or due():
                        response = {"ok": False}
                    else:
                        with log.open("ab", buffering=0) as output:
                            daemon = subprocess.Popen([str(binary), "--config", str(config)],
                                                      stdin=subprocess.DEVNULL, stdout=output,
                                                      stderr=output, start_new_session=True)
                        response["pid"] = daemon.pid
                elif operation == "reload":
                    if not running() or due():
                        stop()
                        response = {"ok": False}
                    else:
                        offset = log.stat().st_size
                        daemon.send_signal(signal.SIGHUP)
                        until = time.monotonic() + 3
                        complete = False
                        while time.monotonic() < until and not due():
                            if not running():
                                break
                            with log.open("rb") as reader:
                                reader.seek(offset)
                                outcome = reader.read(65536)
                            if b"credential reload refused" in outcome:
                                break
                            if b"credentials reloaded\n" in outcome:
                                complete = True
                                break
                            time.sleep(0.01)
                        if not complete:
                            stop()
                        response = {"ok": complete}
                elif operation == "status":
                    response["running"] = running()
                elif operation == "stop":
                    stop()
                elif operation == "close":
                    send(channel, response)
                    return
                else:
                    return
                send(channel, response)
    finally:
        stop()
        channel.close()


class Supervisor:
    """Synchronous control channel, used by one serialized provider writer."""

    def __init__(self, binary: Path, config: Path, log: Path) -> None:
        self.channel, child = socket.socketpair()
        self.channel.settimeout(5)
        try:
            self.process = subprocess.Popen(
                [sys.executable, str(Path(__file__).resolve()), "--fd", str(child.fileno()),
                 "--binary", str(binary), "--config", str(config), "--log", str(log)],
                pass_fds=(child.fileno(),), stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        except BaseException:
            self.channel.close()
            raise
        finally:
            child.close()
        self.buffer = b""

    def call(self, operation: str, **fields: object) -> dict:
        send(self.channel, {"op": operation, **fields})
        while b"\n" not in self.buffer:
            chunk = self.channel.recv(16384)
            if not chunk:
                raise RuntimeError("daemon supervisor unavailable")
            self.buffer += chunk
            if len(self.buffer) > 16384:
                raise RuntimeError("invalid supervisor response")
        line, self.buffer = self.buffer.split(b"\n", 1)
        result = json.loads(line)
        if not result["ok"]:
            raise RuntimeError("daemon enforcement unconfirmed")
        return result

    def close(self) -> None:
        try:
            self.call("close")
        finally:
            self.channel.close()
            try:
                self.process.wait(timeout=6)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=5)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fd", type=int, required=True)
    for field in ("binary", "config", "log"):
        parser.add_argument("--" + field, type=Path, required=True)
    arguments = parser.parse_args()
    supervise(socket.socket(fileno=arguments.fd), arguments.binary,
              arguments.config, arguments.log)


if __name__ == "__main__":
    main()
