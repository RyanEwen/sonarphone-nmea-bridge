#!/usr/bin/env python3
"""
SP200A protocol validator / NMEA bridge (desktop, step 0).

Run from a machine joined to the sonar AP (e.g. SonarPhone_65C0; McKeown-era units use T-BOX-nnn). No dependencies
beyond the stdlib. Python 3.8+.

What it does:
  1. Sends FX to 192.168.1.1:5000 until REDYFX arrives; prints serial + master MAC.
  2. Sends FC (meters, auto-range, 20 deg) using that MAC; re-sends every 10 s.
  3. Parses the REDYFC stream; prints depth / temp / battery / packet size.
  4. Emits NMEA ($SDDPT, $SDDBT, $YXMTW) on a TCP server (default :10110) and,
     optionally, UDP broadcast (--udp-broadcast 2000) so you can point the
     Navionics app on your phone at this machine as an end-to-end test.
  5. --log-raw dumps every REDYFC frame (incl. the 758 echo samples) to a file.

Usage:
  python3 sp200a_validate.py                       # console only + TCP :10110
  python3 sp200a_validate.py --udp-broadcast 2000  # also UDP bcast for Navionics
  python3 sp200a_validate.py --log-raw frames.bin  # capture raw frames
  python3 sp200a_validate.py --host 192.168.1.1 --port 5000 --tcp-port 10110
"""

import argparse
import socket
import struct
import sys
import threading
import time

# ---------------------------------------------------------------- protocol

FX = bytes([0x46, 0x58, 0x15, 0x00] + [0x00] * 15 + [0xB3] + [0x00] * 9)
assert len(FX) == 29

def build_fc(mac: bytes, units_feet: bool = False, depth_min: int = 0,
             depth_max: int = 0, beam_20deg: bool = True) -> bytes:
    """FC request: settings + master MAC + additive 16-bit checksum."""
    b = bytearray(29)
    b[0:2] = b"FC"
    b[2:4] = bytes([0x15, 0x00])
    b[4:6] = bytes([0xF4, 0x02])
    b[6:8] = struct.pack("<H", depth_min)
    b[8:10] = struct.pack("<H", depth_max)          # 0 = auto range
    b[11] = 0x01 if units_feet else 0x00
    b[13] = 0x08 if beam_20deg else 0x02
    b[19:21] = struct.pack("<H", sum(b[0:19]))       # checksum over bytes 0..18
    b[21:27] = mac
    return bytes(b)

def parse_reply(data: bytes):
    """Return (tag, dict) for REDYFX / REDYFC / BUSY, else (None, None)."""
    if len(data) >= 10 and data[6:10] == b"BUSY":
        return "BUSY", {}
    if len(data) >= 32 and data[6:12] == b"REDYFX":
        return "REDYFX", {
            "serial": data[16:26].decode("ascii", "replace"),
            "mac": data[26:32],
        }
    if len(data) >= 38 and data[6:12] == b"REDYFC":
        whole = struct.unpack_from("<H", data, 23)[0]
        return "REDYFC", {
            "size": len(data),
            "units_feet": data[21] == 1,
            "depth": whole + data[25] / 100.0,
            "temp_c": data[26],
            "vbatt": data[30] + data[31] / 100.0,
            "beam": data[32],
            "range_min": struct.unpack_from("<H", data, 16)[0],
            "range_max": struct.unpack_from("<H", data, 18)[0],
            "echo": data[38:],
        }
    return None, None

# ---------------------------------------------------------------- NMEA

def nmea(body: str) -> bytes:
    cs = 0
    for ch in body:
        cs ^= ord(ch)
    return f"${body}*{cs:02X}\r\n".encode("ascii")

def nmea_sentences(depth_m: float, temp_c: float):
    ft, fa = depth_m * 3.28084, depth_m * 0.546807
    return [
        nmea(f"SDDPT,{depth_m:.2f},0.0"),
        nmea(f"SDDBT,{ft:.1f},f,{depth_m:.2f},M,{fa:.1f},F"),
        nmea(f"YXMTW,{temp_c:.1f},C"),
    ]

class NmeaTcpServer(threading.Thread):
    """Minimal multi-client TCP pusher (what Navionics pairs to)."""
    def __init__(self, port: int):
        super().__init__(daemon=True)
        self.port, self.clients, self.lock = port, [], threading.Lock()

    def run(self):
        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind(("0.0.0.0", self.port))
        srv.listen(4)
        print(f"[nmea] TCP server on 0.0.0.0:{self.port}")
        while True:
            c, addr = srv.accept()
            print(f"[nmea] client connected: {addr}")
            with self.lock:
                self.clients.append(c)

    def send(self, payload: bytes):
        with self.lock:
            for c in self.clients[:]:
                try:
                    c.sendall(payload)
                except OSError:
                    self.clients.remove(c)
                    print("[nmea] client dropped")

# ---------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--host", default="192.168.1.1")
    ap.add_argument("--port", type=int, default=5000)
    ap.add_argument("--tcp-port", type=int, default=10110)
    ap.add_argument("--udp-broadcast", type=int, metavar="PORT",
                    help="also broadcast NMEA on this UDP port (e.g. 2000)")
    ap.add_argument("--feet", action="store_true",
                    help="request feet from T-Box (NMEA output stays multi-unit)")
    ap.add_argument("--log-raw", metavar="FILE",
                    help="append raw REDYFC frames (u16 len prefix) to FILE")
    args = ap.parse_args()

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("0.0.0.0", 0))
    sock.settimeout(2.0)
    dest = (args.host, args.port)

    tcp = NmeaTcpServer(args.tcp_port)
    tcp.start()

    bsock = None
    if args.udp_broadcast:
        bsock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        bsock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        print(f"[nmea] UDP broadcast on :{args.udp_broadcast}")

    raw = open(args.log_raw, "ab") if args.log_raw else None

    # --- phase 1: FX until REDYFX
    mac = None
    print(f"[fx] querying {dest[0]}:{dest[1]} ...")
    while mac is None:
        sock.sendto(FX, dest)
        try:
            data, _ = sock.recvfrom(2048)
        except socket.timeout:
            print("[fx] timeout, retrying (on T-BOX WiFi? box powered?)")
            continue
        tag, info = parse_reply(data)
        if tag == "REDYFX":
            mac = info["mac"]
            mac_s = ":".join(f"{b:02x}" for b in mac)
            print(f"[fx] serial={info['serial']}  master MAC={mac_s}")
            if mac == b"\x11" * 6:
                sys.exit("[fx] no master set (post-factory-reset). Run the "
                         "official app once to establish a master, then retry.")
        elif tag == "BUSY":
            print("[fx] BUSY, retrying")
        time.sleep(1.0)

    fc = build_fc(mac, units_feet=args.feet)
    to_meters = 0.3048 if args.feet else 1.0

    # --- phase 2: FC + stream
    last_fc = 0.0
    last_emit = 0.0
    last_depth = None
    last_temp = None
    n = 0
    print("[fc] streaming (Ctrl-C to stop)")
    while True:
        now = time.time()
        if now - last_fc >= 10.0:
            sock.sendto(fc, dest)
            last_fc = now
            print("[fc] request sent")
        try:
            data, _ = sock.recvfrom(4096)
        except socket.timeout:
            print("[fc] stream silent >2s")
            continue
        tag, info = parse_reply(data)
        if tag == "REDYFC":
            n += 1
            last_depth = info["depth"] * to_meters
            last_temp = float(info["temp_c"])
            if raw:
                raw.write(struct.pack("<H", info["size"]) + data)
            if n % 10 == 1:
                print(f"[fc] #{n} size={info['size']}B depth={last_depth:.2f}m "
                      f"temp={last_temp:.0f}C vbatt={info['vbatt']:.2f}V "
                      f"range={info['range_min']}-{info['range_max']} "
                      f"beam=0x{info['beam']:02x} echo={len(info['echo'])}pts")
        elif tag == "BUSY":
            print("[fc] BUSY")
        elif tag is None and data:
            print(f"[??] unknown {len(data)}B: {data[:16].hex(' ')} ...")

        # emit NMEA: on fresh data, and at least every 4 s (Navionics keepalive)
        if last_depth is not None and now - last_emit >= 1.0:
            stale = now - last_emit < 4.0 and tag != "REDYFC"
            if not stale:
                payload = b"".join(nmea_sentences(last_depth, last_temp))
                tcp.send(payload)
                if bsock:
                    bsock.sendto(payload, ("255.255.255.255", args.udp_broadcast))
                last_emit = now

if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\nbye")
