#!/usr/bin/env python3
"""
Prueba de la Fase 2: recibe en la PC las posiciones GPS que manda la app por UDP.

Uso:
    python3 tools/udp_gps_receiver.py <IP_del_telefono> [--port 5001]

La PC saluda primero: manda "SUBSCRIBE" al teléfono cada 2 s. El teléfono
responde "SUBSCRIBED" y, mientras está grabando, manda una línea por fix:

    GPS,<t_ns>,<lat>,<lon>,<alt>,<speed>,<unix_ns>

Que el teléfono responda "SUBSCRIBED" prueba la red aunque todavía no haya
fixes: si no responde, el problema es de conexión y no de GPS.

parse_gps_line() está pensada para copiarse tal cual al pipeline
(realtime/sources.py) cuando se implemente la fuente en vivo.
"""
import argparse
import socket
import time
from typing import NamedTuple, Optional


class GpsFix(NamedTuple):
    t_ns: int       # reloj monótono del teléfono: el que hay que usar para alinear con los frames
    lat: float      # grados
    lon: float      # grados
    alt: float      # metros
    speed: float    # m/s, Doppler
    unix_ns: int    # hora de pared del fix, solo como referencia


def parse_gps_line(line: str) -> Optional[GpsFix]:
    """Convierte una línea 'GPS,...' en un GpsFix. Devuelve None si no es válida."""
    parts = line.strip().split(",")
    if len(parts) != 7 or parts[0] != "GPS":
        return None
    try:
        return GpsFix(int(parts[1]), float(parts[2]), float(parts[3]),
                      float(parts[4]), float(parts[5]), int(parts[6]))
    except ValueError:
        return None


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("phone_ip", help="IP del teléfono en la red local")
    ap.add_argument("--port", type=int, default=5001)
    args = ap.parse_args()
    phone = (args.phone_ip, args.port)

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.settimeout(0.5)

    start = time.monotonic()
    last_subscribe = 0.0
    subscribed = False
    warned = False
    n = 0
    first_t = prev_t = None

    print(f"Saludando a {phone[0]}:{phone[1]} ... (Ctrl+C para terminar)")
    try:
        while True:
            now = time.monotonic()
            if now - last_subscribe >= 2.0:
                sock.sendto(b"SUBSCRIBE", phone)
                last_subscribe = now
            if not subscribed and not warned and now - start > 6.0:
                print("El teléfono no responde. Revisar: misma red WiFi, IP correcta, "
                      "y la app abierta en la pantalla de video.")
                warned = True

            try:
                data, _ = sock.recvfrom(2048)
            except socket.timeout:
                continue
            message = data.decode("utf-8", errors="replace").strip()

            if message == "SUBSCRIBED":
                if not subscribed:
                    print("Conectado. Esperando posiciones (hay que estar grabando).")
                    subscribed = True
                continue

            fix = parse_gps_line(message)
            if fix is None:
                print(f"Línea no reconocida: {message!r}")
                continue

            n += 1
            dt = (fix.t_ns - prev_t) / 1e6 if prev_t is not None else float("nan")
            prev_t = fix.t_ns
            if first_t is None:
                first_t = fix.t_ns
            span = (fix.t_ns - first_t) / 1e9
            rate = (n - 1) / span if span > 0 else float("nan")
            print(f"#{n:4d}  lat {fix.lat:.7f}  lon {fix.lon:.7f}  "
                  f"v {fix.speed:5.2f} m/s  dt {dt:7.1f} ms  tasa {rate:4.2f} Hz")
    except KeyboardInterrupt:
        print(f"\n{n} posiciones recibidas.")


if __name__ == "__main__":
    main()
