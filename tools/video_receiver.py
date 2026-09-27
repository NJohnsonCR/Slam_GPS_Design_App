#!/usr/bin/env python3
"""
Receive the video the app streams over TCP, show it, and report frame rate,
bandwidth and delay once per second.

Usage:
    python tools/video_receiver.py <phone_ip> [--port 5000] [--no-window]

Needs OpenCV and NumPy. Over USB instead of WiFi:
    adb forward tcp:5000 tcp:5000
    python tools/video_receiver.py 127.0.0.1

The phone first sends one text line,

    HELLO,<width>,<height>,<fx>,<fy>,<cx>,<cy>,<fps>

and then, for each frame, a 20-byte big-endian header and the JPEG:

    uint32 JPEG length | uint64 capture time, phone boot clock (ns) | uint64 capture time, Unix (ns)

"Edad" is the PC time on arrival minus the Unix capture time, so it includes any
offset between the two clocks. For a measurement that does not depend on the clocks,
point the phone at this window: the PC clock drawn on each frame appears again inside
the next ones, and the difference between the two is the camera-to-screen delay.
"""
import argparse
import socket
import statistics
import struct
import time
from typing import BinaryIO, NamedTuple, Optional

import cv2
import numpy as np

HEADER = struct.Struct(">IQQ")  # JPEG length, boot-clock ns, Unix ns
# ASCII only: OpenCV's Qt backend does not find windows whose name has accents,
# and imshow() then opens a new window for every frame.
WINDOW = "Video del telefono"


class Hello(NamedTuple):
    width: int
    height: int
    fx: float
    fy: float
    cx: float
    cy: float
    fps: int


def parse_hello(line: str) -> Optional[Hello]:
    """Parse the 'HELLO,...' line. Returns None if the line is malformed."""
    parts = line.strip().split(",")
    if len(parts) != 8 or parts[0] != "HELLO":
        return None
    try:
        return Hello(int(parts[1]), int(parts[2]), float(parts[3]), float(parts[4]),
                     float(parts[5]), float(parts[6]), int(parts[7]))
    except ValueError:
        return None


def read_exact(stream: BinaryIO, n: int) -> bytes:
    data = stream.read(n)
    if len(data) < n:
        raise ConnectionError("el teléfono cerró la conexión")
    return data


def draw_clock(image: np.ndarray) -> None:
    """Draw the PC time in the bottom-left corner, large enough to read when filmed."""
    now = time.time()
    text = time.strftime("%H:%M:%S", time.localtime(now)) + f".{int(now * 1000) % 1000:03d}"
    origin = (20, image.shape[0] - 30)
    cv2.putText(image, text, origin, cv2.FONT_HERSHEY_SIMPLEX, 2.0, (0, 0, 0), 10, cv2.LINE_AA)
    cv2.putText(image, text, origin, cv2.FONT_HERSHEY_SIMPLEX, 2.0, (255, 255, 255), 3,
                cv2.LINE_AA)


def window_is_open() -> bool:
    try:
        return cv2.getWindowProperty(WINDOW, cv2.WND_PROP_VISIBLE) >= 1
    except cv2.error:
        return False


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("phone_ip", help="phone IP, or 127.0.0.1 with adb forward")
    ap.add_argument("--port", type=int, default=5000)
    ap.add_argument("--no-window", action="store_true", help="only print the statistics")
    args = ap.parse_args()

    print(f"Conectando a {args.phone_ip}:{args.port} ...")
    try:
        sock = socket.create_connection((args.phone_ip, args.port), timeout=5)
    except OSError as e:
        print(f"No se pudo conectar ({e}). Revisar: misma red WiFi, IP correcta, "
              "y la app abierta en la pantalla de video.")
        return
    sock.settimeout(10)     # a phone that stops sending ends the script instead of hanging it
    stream = sock.makefile("rb")

    hello = parse_hello(stream.readline().decode("ascii", errors="replace"))
    if hello is None:
        print("El teléfono no envió un saludo válido.")
        return
    print(f"Cámara: {hello.width}x{hello.height} a {hello.fps} fps  "
          f"fx {hello.fx:.2f}  fy {hello.fy:.2f}  cx {hello.cx:.2f}  cy {hello.cy:.2f}")
    print("Ctrl+C (o 'q' en la ventana) para terminar.")

    if not args.no_window:
        cv2.namedWindow(WINDOW)
    interval_ns = 1e9 / hello.fps
    frames = missing = total_bytes = 0
    ages = []
    win_frames = win_bytes = 0
    win_ages = []
    prev_t = None
    start = win_start = time.monotonic()
    try:
        while True:
            length, t_ns, unix_ns = HEADER.unpack(read_exact(stream, HEADER.size))
            jpeg = read_exact(stream, length)
            age_ms = (time.time_ns() - unix_ns) / 1e6

            # Frames the phone captured but did not send show up as gaps in its timestamps.
            if prev_t is not None:
                missing += max(0, round((t_ns - prev_t) / interval_ns) - 1)
            prev_t = t_ns
            size = HEADER.size + length
            frames += 1
            total_bytes += size
            ages.append(age_ms)
            win_frames += 1
            win_bytes += size
            win_ages.append(age_ms)

            if not args.no_window:
                image = cv2.imdecode(np.frombuffer(jpeg, np.uint8), cv2.IMREAD_COLOR)
                if image is None:
                    print("JPEG inválido; se descarta.")
                    continue
                draw_clock(image)
                cv2.imshow(WINDOW, image)
                if cv2.waitKey(1) & 0xFF == ord("q"):
                    break
                # Closing the window ends the script; otherwise imshow() would reopen it.
                if not window_is_open():
                    break

            now = time.monotonic()
            if now - win_start >= 1.0:
                span = now - win_start
                print(f"{win_frames / span:5.1f} fps | {win_bytes / span / 1e6:5.2f} MB/s | "
                      f"{win_bytes / win_frames / 1024:4.0f} KB/frame | "
                      f"edad {statistics.median(win_ages):5.0f} ms (máx {max(win_ages):5.0f}) | "
                      f"faltan {missing}")
                win_frames = win_bytes = 0
                win_ages = []
                win_start = now
    except KeyboardInterrupt:
        pass
    except (ConnectionError, socket.timeout) as e:
        print(f"Conexión terminada: {e}")
    finally:
        sock.close()
        cv2.destroyAllWindows()

    elapsed = time.monotonic() - start
    if frames:
        p95 = sorted(ages)[int(0.95 * (len(ages) - 1))]
        print(f"\n{frames} frames en {elapsed:.1f} s: {frames / elapsed:.1f} fps, "
              f"{total_bytes / elapsed / 1e6:.2f} MB/s, faltan {missing}. "
              f"Edad mediana {statistics.median(ages):.0f} ms, p95 {p95:.0f} ms.")


if __name__ == "__main__":
    main()
