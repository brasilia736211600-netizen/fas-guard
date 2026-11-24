# FAS Guard — transport engineering & test policy

## Why 1.0–1.3 failed on-device

The APK pipeline only proved that the code compiles. It did not prove that the userspace TCP relay behaved like TCP. Four defects were shipped:

| # | Defect | Effect |
|---|---|---|
| 1 | SYN-ACK used `serverSeq`, and the SYN was not counted; the first data packet reused that sequence number | Every client discarded all downstream data. DNS (UDP) still worked, so the app looked “connected” while browsing died. |
| 2 | Direct upstream sockets kept a 15 s `SO_TIMEOUT` | Idle keep-alive, streaming and slow responses were reset after 15 s. |
| 3 | SOCKS5 greeting sent 4 bytes for `nmethods=1` | Unauthenticated SOCKS5 handshakes failed (the proxy saw a stray byte). |
| 4 | No receive-window accounting | On fast downloads the relay could write past the client's window; without retransmission this stalls the connection. |

## Invariants now enforced

1. SYN consumes one sequence number; server data begins at `SYN.SEQ + 1`.
2. Pure ACKs use the current server sequence; payload advances it by exactly the byte count.
3. Sequence arithmetic is modulo 2³² and duplicate/gap segments are answered with the current ACK.
4. The relay sends at most `client_window - inflight` bytes and waits for ACKs/window updates.
5. Client FIN performs a half-close; the server→client direction stays open until EOF, then FIN.
6. Sequence state is updated under a per-flow lock so the pump and the client-data path cannot interleave packets.
7. Outbound TCP/UDP checksums include the IPv4 pseudo-header.
8. Direct sockets have no read timeout after connect; proxy handshakes keep a bounded timeout.
9. DNS answers are TTL-capped, route-scoped, and rewritten for the current query ID/question case.

## Executable tests (`tools/run-tests.sh`, mandatory in CI)

- `NetTest`: independent IPv4/TCP/UDP checksum verification, lengths, ports, protocol.
- `TcpRelayTest`: a real loopback server plus a simulated client verifies SYN-ACK, `SYN+1` sequencing, request delivery, response reassembly, half-close, final FIN, and a 10-byte receive window (no overrun before ACKs).
- `DnsCacheTest`: case-insensitive keys, ID/question rewrite, TTL aging, short-TTL expiry, and no negative caching.

A deliberately reintroduced version of defect #1 (removing the `SYN+1` increment) makes `TcpRelayTest` fail, so the test is known to catch the production bug rather than merely exercising happy code.

## References / proven practice

- RFC 793 §3.3 (sequence numbers, SYN/FIN consumption) and RFC 1928 §3 (SOCKS5 greeting length).
- Android `VpnService` docs: TUN is a packet interface; TCP state must be maintained in userspace, and upstream sockets must be `protect()`ed.
- Patterns used by NetGuard and tun2socks-class relays: immediate SYN-ACK with buffered client data, independent receive-window accounting, explicit half-close, and RST on upstream failure.
- No GPL code was copied; the implementation is original and the test harness is independent.

## Remaining transport limitations (documented, not hidden)

- No retransmission timer or TIME_WAIT state: acceptable while the TUN path is lossless and flow control prevents window overrun; revisit if device logs show drops.
- No window-scale negotiation: we advertise 65535 and do not echo wscale, so both sides stay unscaled.
- No TCP options (SACK/timestamps); clients fall back to baseline TCP.
- UDP-over-SOCKS5 and IPv6 relay remain out of scope until P4/P6.
