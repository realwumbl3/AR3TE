#!/usr/bin/env python3
import json
import socket
import platform

PORT = 45678
DISCOVER_MSG = b"RDESK_DISCOVER"


def get_local_ipv4() -> str:
    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        probe.connect(("8.8.8.8", 80))
        return probe.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        probe.close()


def main() -> None:
    machine_name = platform.node()
    local_ip = get_local_ipv4()

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("", PORT))

    print("Remote desktop discovery server running")
    print(f"  Machine: {machine_name}")
    print(f"  Address: {local_ip}:{PORT}")
    print("  Waiting for discovery requests on the local network...")

    while True:
        data, remote = sock.recvfrom(1024)
        if data != DISCOVER_MSG:
            continue

        payload = json.dumps(
            {
                "type": "rdesk",
                "name": machine_name,
                "host": local_ip,
                "port": PORT,
            }
        ).encode("utf-8")
        sock.sendto(payload, remote)


if __name__ == "__main__":
    main()
