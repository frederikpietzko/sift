#!/usr/bin/env python3
"""Opt-in fixed-port TCP relays to host loopback services; no HTTP parsing or traffic logs."""
import argparse
import asyncio
import contextlib
import ipaddress

PORTS = {"model": (29516, 19516), "search": (28888, 8888), "messaging": (25672, 5672)}


async def copy_stream(reader, writer):
    while data := await reader.read(65536):
        writer.write(data)
        await writer.drain()
    if writer.can_write_eof():
        writer.write_eof()


async def forward(reader, writer, port, networks):
    peer = ipaddress.ip_address(writer.get_extra_info("peername")[0])
    upstream = None
    pumps = []
    try:
        if not any(peer in network for network in networks):
            return
        source, upstream = await asyncio.wait_for(asyncio.open_connection("127.0.0.1", port), timeout=5)
        pumps = [asyncio.create_task(copy_stream(reader, upstream)), asyncio.create_task(copy_stream(source, writer))]
        await asyncio.wait_for(asyncio.gather(*pumps), timeout=25 * 3600)
    except (OSError, asyncio.TimeoutError):
        pass
    finally:
        for task in pumps:
            task.cancel()
        await asyncio.gather(*pumps, return_exceptions=True)
        if upstream:
            upstream.close()
            with contextlib.suppress(OSError):
                await upstream.wait_closed()
        writer.close()
        with contextlib.suppress(OSError):
            await writer.wait_closed()


async def serve(bind, networks, services):
    servers = []
    active = set()

    async def accept(reader, writer, port):
        if len(active) >= 128:
            writer.close()
            await writer.wait_closed()
            return
        task = asyncio.current_task()
        active.add(task)
        try:
            await forward(reader, writer, port, networks)
        finally:
            active.remove(task)

    try:
        for service in services:
            listener, upstream = PORTS[service]
            async def handler(reader, writer, port=upstream):
                await accept(reader, writer, port)
            servers.append(await asyncio.start_server(handler, bind, listener))
            print(f"Relay {service}: {bind}:{listener} -> loopback:{upstream}; source CIDRs enforced", flush=True)
        await asyncio.gather(*(server.serve_forever() for server in servers))
    finally:
        for server in servers:
            server.close()
            await server.wait_closed()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bind", required=True, type=ipaddress.IPv4Address,
                        help="Specific host interface address reachable from Docker (no wildcard)")
    parser.add_argument("--allow-client", required=True, action="append", type=ipaddress.ip_network,
                        help="Observed Docker source IP/CIDR; repeat as needed, never /0")
    parser.add_argument("--service", required=True, action="append", choices=PORTS)
    args = parser.parse_args()
    if args.bind.is_unspecified or args.bind.is_multicast or any(n.prefixlen == 0 for n in args.allow_client):
        parser.error("Wildcard bind/source networks are forbidden")
    try:
        asyncio.run(serve(str(args.bind), args.allow_client, set(args.service)))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()