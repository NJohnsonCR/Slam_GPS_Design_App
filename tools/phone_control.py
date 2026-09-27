#!/usr/bin/env python3
"""
Start or stop recording on the phone from the PC.

Usage:
    python3 tools/phone_control.py <phone_ip> start|stop [--port 5001]

Sends "START" or "STOP" to the phone and repeats it every 0.5 s until the phone
reports the expected state, so a lost datagram does no harm:

    STATE,RECORDING,<folder>    recording into that folder on the phone
    STATE,IDLE                  not recording; the phone keeps streaming

Repeating a command is safe: the phone only acts when its state has to change.
A program that also receives the stream should send the commands from the same
socket it subscribes with.
"""
import argparse
import socket
import sys
import time

RETRY_S = 0.5
TIMEOUT_S = 5.0


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("phone_ip", help="phone IP on the local network")
    ap.add_argument("command", choices=["start", "stop"])
    ap.add_argument("--port", type=int, default=5001)
    args = ap.parse_args()
    phone = (args.phone_ip, args.port)
    command = args.command.upper().encode("ascii")
    wanted = "STATE,RECORDING" if args.command == "start" else "STATE,IDLE"

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.settimeout(RETRY_S)
    deadline = time.monotonic() + TIMEOUT_S
    last_state = None
    while time.monotonic() < deadline:
        sock.sendto(command, phone)
        try:
            data, _ = sock.recvfrom(256)
        except socket.timeout:
            continue
        message = data.decode("utf-8", errors="replace").strip()
        if not message.startswith("STATE,"):
            continue
        last_state = message
        if message.startswith(wanted):
            if args.command == "start":
                print(f"Grabando en la carpeta {message.split(',', 2)[2]} del teléfono.")
            else:
                print("Grabación detenida.")
            return

    if last_state is None:
        print("El teléfono no responde. Revisar: misma red WiFi, IP correcta, "
              "y la app abierta en la pantalla de video.")
    else:
        print(f"El teléfono no cambió de estado (responde {last_state}). "
              "¿Está la app en primer plano, en la pantalla de video?")
    sys.exit(1)


if __name__ == "__main__":
    main()
