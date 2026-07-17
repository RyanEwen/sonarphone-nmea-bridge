#!/usr/bin/env python3
"""Find and connect the phone's wireless-adb endpoint — retry-hardened.

Why this exists (vs. the persistent project's adb-discover.py): scanning the
LAN from WSL2 goes host -> Windows NAT -> LAN, and under a high-concurrency
SYN flood that path silently DROPS packets. A dropped SYN looks like a closed
port (connect times out), so a single-pass scan gets FALSE NEGATIVES — it
missed a phone whose adb port was wide open (observed 2026-07-17: port 33769
in the scanned range reported closed twice).

The fix: distinguish the two failure modes.
  - Connection *refused* (RST) => the port is genuinely closed. Trust it.
  - Connection *timeout* => could be a real filtered/absent port OR a dropped
    SYN. Re-probe these, a couple more passes, at LOWER concurrency (fewer
    drops). A port that's actually open will answer on one of the retries.

Then every port that ever answered is tested with `adb connect` (polling until
adb reports it `device`, since the TLS handshake completes a beat after connect).

Usage:
  find-phone.py [host] [--quiet]
    host     phone IP or IP:port to try first (default: saved / 192.168.1.61)
    --quiet  print only the final host:port
Prints the connected endpoint on stdout; exits non-zero if nothing was found.
"""
import argparse
import asyncio
import os
import re
import subprocess
import sys
import time
from pathlib import Path

ADB = os.environ.get("ADB") or "adb"
STATE = Path.home() / ".android" / "adb-endpoint"

# Android wireless-debug lands somewhere in the ephemeral/dynamic range; scan
# wide because it has been observed both mid-range (33769) and high (50000+).
SCAN_LO, SCAN_HI = 30000, 65535
PASS1_CONCURRENCY = 120     # modest: high floods drop SYNs through WSL NAT
RETRY_CONCURRENCY = 48      # even gentler for the retry passes
RETRY_PASSES = 3            # re-probe timed-out ports this many extra times
CONNECT_TIMEOUT = 1.5


def log(msg):
    if not QUIET:
        print(msg, file=sys.stderr)


def run_adb(*args, timeout=10.0):
    try:
        out = subprocess.run([ADB, *args], capture_output=True, text=True, timeout=timeout)
        return (out.stdout or "") + (out.stderr or "")
    except Exception as exc:
        return f"__error__ {exc}"


def connected_endpoints():
    found = set()
    for line in run_adb("devices").splitlines():
        m = re.match(r"^(\d+\.\d+\.\d+\.\d+:\d+)\s+device\b", line.strip())
        if m:
            found.add(m.group(1))
    return found


def try_connect(endpoint, settle=5.0):
    out = run_adb("connect", endpoint)
    if "refused" in out.lower() or "cannot connect" in out.lower() or "failed" in out.lower():
        return False
    waited = 0.0
    while waited < settle:
        if endpoint in connected_endpoints():
            return True
        time.sleep(0.4)
        waited += 0.4
    if endpoint not in connected_endpoints():
        run_adb("disconnect", endpoint)  # don't leave 'offline' junk behind
    return endpoint in connected_endpoints()


# Probe outcomes
OPEN, REFUSED, TIMEOUT = "open", "refused", "timeout"


async def probe(host, port, sem):
    """Return (port, outcome). REFUSED is trustworthy; TIMEOUT may be a drop."""
    async with sem:
        try:
            fut = asyncio.open_connection(host, port)
            _, w = await asyncio.wait_for(fut, CONNECT_TIMEOUT)
            w.close()
            try:
                await w.wait_closed()
            except Exception:
                pass
            return port, OPEN
        except (ConnectionRefusedError, OSError) as exc:
            # ECONNREFUSED => genuinely closed; other OSErrors treated as timeout
            if isinstance(exc, ConnectionRefusedError) or getattr(exc, "errno", None) == 111:
                return port, REFUSED
            return port, TIMEOUT
        except (asyncio.TimeoutError, Exception):
            return port, TIMEOUT


async def scan_ports(host, ports, concurrency):
    sem = asyncio.Semaphore(concurrency)
    results = await asyncio.gather(*(probe(host, p, sem) for p in ports))
    openp = [p for p, o in results if o == OPEN]
    timedout = [p for p, o in results if o == TIMEOUT]
    return openp, timedout


async def find_open_ports(host):
    """Full scan + retry-on-timeout so dropped SYNs don't hide an open port."""
    log(f"scanning {host}:{SCAN_LO}-{SCAN_HI} (pass 1, {PASS1_CONCURRENCY}-way)...")
    openp, timedout = await scan_ports(host, range(SCAN_LO, SCAN_HI + 1), PASS1_CONCURRENCY)
    found = set(openp)
    log(f"  pass 1: {len(found)} open, {len(timedout)} timeouts to retry")
    for i in range(RETRY_PASSES):
        if not timedout:
            break
        openp, timedout = await scan_ports(host, timedout, RETRY_CONCURRENCY)
        found.update(openp)
        log(f"  retry {i + 1}: +{len(openp)} open ({len(timedout)} still timing out)")
    return sorted(found)


def split_hostport(entry):
    if ":" in entry:
        h, p = entry.rsplit(":", 1)
        return h, (int(p) if p.isdigit() else None)
    return entry, None


def save_state(endpoint):
    try:
        STATE.parent.mkdir(parents=True, exist_ok=True)
        history = []
        if STATE.exists():
            history = [l.strip() for l in STATE.read_text().splitlines() if l.strip()]
        ordered = [endpoint] + [h for h in history if h != endpoint]
        STATE.write_text("\n".join(ordered[:5]) + "\n")
    except Exception as exc:
        log(f"  (could not save state: {exc})")


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("host", nargs="?", help="phone IP or IP:port to try first")
    ap.add_argument("--quiet", action="store_true")
    args = ap.parse_args()
    global QUIET
    QUIET = args.quiet

    # candidate host: arg, else saved last-good, else a sensible default
    host = None
    port_hint = None
    if args.host:
        host, port_hint = split_hostport(args.host)
    elif STATE.exists():
        for line in STATE.read_text().splitlines():
            if line.strip():
                host, port_hint = split_hostport(line.strip())
                break
    host = host or "192.168.1.61"

    # already connected?
    for ep in connected_endpoints():
        if ep.startswith(host + ":"):
            log(f"already connected: {ep}")
            print(ep)
            return 0

    # fast path: the exact saved port
    if port_hint and try_connect(f"{host}:{port_hint}"):
        log(f"connected (port unchanged): {host}:{port_hint}")
        save_state(f"{host}:{port_hint}")
        print(f"{host}:{port_hint}")
        return 0

    # retry-hardened scan
    open_ports = await find_open_ports(host)
    log(f"open ports on {host}: {open_ports or 'none'}")
    for port in open_ports:
        ep = f"{host}:{port}"
        if try_connect(ep):
            log(f"connected: {ep}")
            save_state(ep)
            print(ep)
            return 0
        log(f"  {ep} open but not adb (offline) — skipping")

    log("no adb endpoint found. Is Wireless debugging ON for this network?")
    return 1


if __name__ == "__main__":
    QUIET = False
    sys.exit(asyncio.run(main()))
