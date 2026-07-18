#!/usr/bin/env python3
"""Poll the sonarphone's UDP status responder (:19998). Stdlib only."""
import socket
import sys
import time

host = sys.argv[1] if len(sys.argv) > 1 else "192.168.2.33"
count = int(sys.argv[2]) if len(sys.argv) > 2 else 1
interval = float(sys.argv[3]) if len(sys.argv) > 3 else 5.0

s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.settimeout(3.0)
for i in range(count):
    t0 = time.time()
    try:
        s.sendto(b"?", (host, 19998))
        data, _ = s.recvfrom(512)
        rtt = (time.time() - t0) * 1000
        print(f"{time.strftime('%H:%M:%S')} rtt={rtt:.0f}ms {data.decode()}", flush=True)
    except socket.timeout:
        print(f"{time.strftime('%H:%M:%S')} timeout", flush=True)
    if i < count - 1:
        time.sleep(interval)
