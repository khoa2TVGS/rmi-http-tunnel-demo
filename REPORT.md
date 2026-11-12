# RMI over an HTTP tunnel — course project report

**Course:** Network Programming  
**Topic:** tunnelling Java RMI through HTTP/1.1  
**Deliverable:** working three-process system + measurement harness + this report

---

## Abstract

The assignment was to build something around RMI tunnelled through HTTP. The JDK
used to ship exactly that (`sun.rmi.transport.proxy.RMIHttpToCGISocketFactory`),
and it was removed, so the project rebuilds the mechanism end to end: a client, a
relay that is a genuine HTTP server, and an untouched RMI service behind it. The
interesting part is that RMI's wire protocol (JRMP) is a multiplexed, stateful
byte stream that cannot be split into requests, while HTTP is a request/response
protocol — so the tunnel has to carry one *open bidirectional stream* inside a
single HTTP message. The report explains the hand-rolled HTTP/1.1 framing that
makes this work, how it is plugged into `java.rmi.server.RMISocketFactory`, and
what it costs: measured on loopback, chunked framing roughly doubles transport
time (44 -> 98 ms for a 9 MB round trip) but changes end-to-end time by under 10%
when the remote call does real work.

---

## 1. Problem

The original suggestion was "RMI over HTTP". Taken literally it is trivial — wrap
every remote call in its own HTTP request. That design is also useless, because it
destroys RMI: it re-implements the stub protocol instead of tunnelling it, and the
stub is exactly what the assignment wants to keep.

So the requirement I built to is: **the RMI client and server are ordinary RMI code.
No change to the remote interface, no change to the stub, no change to the server.
All that changes is where the bytes go.** Concretely:

1. A stock `Naming.lookup` and a stock remote call must succeed through the tunnel.
2. With the relay stopped, the same call must fail — proving the tunnel is the only
   path and not a decoration next to a working direct connection.
3. The tunnel must survive a real proxy: HTTP/1.1 with `Transfer-Encoding: chunked`
   for the POST form, and `CONNECT` for the classic form.
4. Image bytes must arrive byte-identical, verified by SHA-256 at both ends.

## 2. Background

### 2.1 What RMI puts on the wire

RMI exports an object, which opens a server socket and hands out a stub containing
a `LiveRef` — a host, a port and an object identifier. A call on the stub opens a
TCP connection to that endpoint (or reuses one from its connection pool) and writes
JRMP messages into it: `Call` for a request, `Return` for a reply, `Ping`/`DGCAck`
for distributed garbage collection. Multiple calls share one connection, and the
two directions are in use at the same time.

This is why a tunnel has to be a *stream* tunnel and not a message wrapper.

### 2.2 Why the JDK's version is gone

`RMISocketFactory` is the extension point: install one with
`RMISocketFactory.setSocketFactory(...)` and every client-side RMI connection is
created by it. The JDK once shipped `RMIHttpToPortSocketFactory`,
`RMIHttpToCGISocketFactory` and their server counterparts in
`sun.rmi.transport.proxy`, and they were removed. On the JDK used here
(`java --describe-module java.rmi`) only `java.rmi`, `java.rmi.dgc`,
`java.rmi.registry`, `java.rmi.server` and `javax.rmi.ssl` are exported;
`javap` cannot find the proxy classes at all. The socket factory itself is still
exported and still works — only the HTTP implementation is missing. That gap is
the project.

---

## 3. Design

### 3.1 Three processes

```
+----------------+   HTTP/1.1 :8080    +------------------+   raw TCP    +------------------+
|  client JVM    | ------------------> |  relay JVM       | -----------> |  server JVM      |
|  JavaFX UI     |  POST /tunnel +     |  hand-rolled     |  socket      |  registry :1099  |
|  RMISocket-    |  Transfer-Encoding: |  HTTP server     |  to target   |  object   :1100  |
|  Factory =     |  chunked            |  + allowlist     |              |  both on         |
|  TunnelSocket- |                     |  + chaos knobs   |              |  127.0.0.1       |
|  Factory       |                     |                  |              |                  |
+----------------+                     +------------------+              +------------------+
```

Why a separate relay and not a proxy inside the client? Because the interesting
protocol work lives in the relay, and because it makes the deployment story
believable: the client only needs to reach one port.

### 3.2 The core mismatch

JRMP wants an open, full-duplex byte pipe on one connection. HTTP/1.1 is a
request followed by a response. The only shape of HTTP that can carry a pipe is a
request whose *body* is still open when the response starts — which is exactly
what `Transfer-Encoding: chunked` on both directions gives you:

* the client sends a request head, then keeps writing chunked bytes;
* the relay answers `200 OK` with a chunked body *immediately*, before the request
   body is finished;
* from that moment the two bodies are the two directions of one byte stream.

`Content-Length` is impossible here: neither side knows how many bytes an RMI
connection will carry before it closes.

### 3.3 Why no stock Java HTTP client works

Both obvious candidates fail, and finding out why was the main research step:

* `HttpURLConnection` — calling `getInputStream()` and `getOutputStream()` on
  different threads is not supported; it corrupts or blocks.
* `java.net.http.HttpClient` over HTTP/1.1 — the response body is not released
  until the request publisher completes, which never happens for an endless
  stream. HTTP/2 does have duplex streams, but `com.sun.net.httpserver` (the JDK
  server) speaks HTTP/1.1 only, and the goal is a protocol a real proxy accepts.

So the client speaks HTTP/1.1 by hand on a `Socket`: request line, three headers,
a chunked body. Roughly 60 lines, and every byte is under our control — which is
also what makes the framing reproducible in the report.

### 3.4 Two tunnel forms

| Form | Request | Response | Cost |
| --- | --- | --- | --- |
| **POST + chunked** | `POST /tunnel?target=HOST:PORT` + `Transfer-Encoding: chunked` | `200 OK` + `Transfer-Encoding: chunked` | ~150 lines of framing, survives POST-only proxies |
| **CONNECT** | `CONNECT HOST:PORT` | `200 Connection Established` | ~40 lines, then a raw pipe; proxies must permit CONNECT |

Both are implemented and measured. The chunked form is the default because it is
the one that requires a real protocol implementation; CONNECT is the cheap second
data point that shows how much of the overhead is framing.

### 3.5 Plugging into RMI

`TunnelSocketFactory extends RMISocketFactory`:

* `createSocket(String host, int port)` ignores `host` and dials the relay, encoding
  the real target in the request path; it returns a `TunnelSocket`.
* `createServerSocket(int port)` throws `UnsupportedOperationException` — it must
  never be used, see below.
* `equals`/`hashCode` are overridden because RMI compares factories.

Three RMI facts decide the rest of the design:

1. **The factory latches.** `RMISocketFactory.setSocketFactory` takes effect once and
   the choice lives for the JVM's lifetime. It has to be installed before the first
   RMI call, which is why the UI disables the transport selector after connecting
   and why the benchmark spawns one child JVM per mode.
2. **Only the client installs it.** Installing it on the server would route the
   server's own `exportObject` through the tunnel — a loop. The server keeps the
   default factory and binds to loopback.
3. **The stub carries its own factory when it has one.** Here the stub is exported
   with the default factory, so at call time the client uses the global
   `RMISocketFactory` — the tunnel. This is why the client-side installation is
   enough.

`java.rmi.server.hostname` is unset on purpose: the stub advertises
`127.0.0.1:1100`, the relay resolves that in its own namespace, and the tunnel
factory ignores the advertised host anyway. The name is a label, not a route.

### 3.6 Access control

An HTTP relay that dials any target on request is an SSRF gadget: anyone who can
POST to it can reach anything the relay can reach. Two defences:

* **Default allowlist** = loopback only; `--allow a,b,c` widens it, `*` allows
  everything (used only for demos).
* **RMI bound to loopback** via a custom `RMIServerSocketFactory` that creates
  `ServerSocket(port, backlog, InetAddress.getLoopbackAddress())`. Ports 1099/1100
  are therefore unreachable from any other machine — no firewall rule to forget,
  and no way to bypass the tunnel. `netstat` shows it.

---

## 4. Implementation

One Maven module, three entry points plus shared code:

| Package / class | Role |
| --- | --- |
| `lab.common.ImageLab` | remote interface: `ping`, `describe`, `echo`, `process` |
| `lab.common.ImageResult` | structured reply: bytes + width/height + server-side timings |
| `lab.common.SynthImage`, `FilterSpec`, `Bytes` | deterministic test images, filter parameters, formatting |
| `lab.server.ServerMain`, `ImageLabImpl`, `ImageOps` | registry on 1099, object on 1100, both loopback; filters |
| `lab.tunnel.ChunkedOutputStream` / `ChunkedInputStream` | the chunked codec |
| `lab.tunnel.TunnelSocket` | a `Socket` whose streams are the codec; everything RMI touches (`setSoTimeout`, `close`, `getInetAddress`) is delegated to the real socket |
| `lab.tunnel.TunnelClient` | writes the request head, validates the `200`, returns a `TunnelSocket` |
| `lab.tunnel.TunnelSocketFactory` | the `RMISocketFactory` above |
| `lab.relay.RelayMain` | the HTTP relay: both tunnel forms, allowlist, chaos knobs, byte counters |
| `lab.client.LabClient`, `TunnelClientMain`, `DirectClientMain` | client-side helpers and two CLIs |
| `lab.bench.BenchMain` | measurement matrix -> CSV |
| `lab.ui.LabApp` | JavaFX shell: connection bar + tab per tool |
| `lab.ui.ImageTool`, `BenchTool`, `TunnelTool` | the three tools |
| `lab.tunnel.TunnelProbe`, `WireCapture` | verification tools that do not involve RMI |

### 4.1 The chunked codec

A write of N bytes becomes `hex(N)\r\n`, the bytes, `\r\n`. Reads parse the size
line, consume exactly that many bytes, then the trailing CRLF; a size of `0` means
end of stream and the trailer is drained. Chunks are capped at 32 KB so a single
write never stalls for long behind a slow reader.

The one line that matters most is a flush. JRMP writes a message, flushes, and then
blocks waiting for the reply. If the codec buffers a chunk instead of pushing it,
both sides wait for each other and the connection deadlocks *silently* — no
exception, no CPU, just nothing. `ChunkedOutputStream.write` therefore emits its
chunk and flushes the underlying socket on every write. Reader and writer are
separate threads on both ends.

### 4.2 The relay

Hand-written HTTP, deliberately. The first version used
`com.sun.net.httpserver.HttpServer` and deadlocked on the first byte:

> `jstack` showed the client parked in `TunnelClient.readHead(TunnelClient.java:56)`
> waiting for the status line, while the server had not yet flushed it —
> `HttpServer` releases the response head together with the first body byte, and the
> client sends no body byte until it sees the head. `curl` does not reproduce it
> because curl sends `Expect: 100-continue` and pushes body bytes early, which
> incidentally flushes the head.

The relay therefore writes `HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n`
itself and flushes before pumping. That is the whole trick, and it is the same
trap the JDK's own removed implementation had to solve.

After the handshake the relay runs two pumps (client -> upstream, upstream ->
client) and counts bytes. Chaos knobs, all opt-in through an `X-Tunnel-Chaos`
header on the request, simulate a hostile middlebox:

* `buffer=65536` — release data to the client in fixed blocks (stalls a reader),
* `delay=50` — sleep 50 ms per read,
* `drop=2mb` — discard the first 2 MB downstream (byte-exactness must then fail).

### 4.3 The client application

JavaFX, one connection bar and one tab per tool. The bar owns the transport
(`Direct`, `Tunnel (chunked)`, `Tunnel (CONNECT)`), the endpoint, the relay
address and the chaos knobs. It is editable only while disconnected: Connect
installs the chosen transport, Disconnect closes its sockets and hands the bar
back, so transports can be compared in one session. Each tab is a self-contained
tool that receives the live stub and never cares how it was reached:

* **Image filter** sends a deterministic synthetic image through the selected
  filter (`grayscale`, `posterize`, `sobel`, `blur`, `resize`) and shows both
  images plus three timings: round trip, server work (measured inside the
  service) and transfer (the difference).
* **Transform** sends the same image through a geometry operation (rotate
  90/180/270, flip, crop with a margin slider) and checks the returned pixels.
  Geometry travels inside the existing `transform` call, so the protocol gained
  no new method for it.
* **Histogram** receives four 256-bin arrays (red, green, blue, luma) and draws
  them on a canvas, with mean luma and peak bin. The reply is a few kilobytes of
  numbers against a megabyte-scale request, which is the mirror image of the
  filter tools.
* **Palette** asks the service for a k-means colour quantisation (2 to 32 colours)
  and shows the quantised image next to its palette swatches.
* **Benchmark** runs the matrix and plots transfer time by payload and mode; each
  (workload, mode) combination is a separate child JVM because the global factory
  can only be installed once per process.
* **Tunnel inspector** exercises the transport with RMI removed: it stands up a
  throw-away echo server on loopback, reaches it through the relay, verifies
  SHA-256 on both directions, and can print the bytes of one handshake. Failures
  here belong to the codec, failures in the other tabs belong to RMI -- which is
  the point of having it as its own tool.

### 4.4 Switching transports in a running client

RMI consults the global `RMISocketFactory` for each new connection, but
`RMISocketFactory.setSocketFactory` succeeds only once. The client therefore
installs a `SwitchableSocketFactory` on the first Connect and only retargets it
afterwards: the wrapper holds a volatile delegate plus a list of live sockets,
and Disconnect calls `closeAll()`. A test that swapped the delegate for one
which refuses everything showed the next call failing inside
`TCPEndpoint.newSocket` -- proof that the factory is consulted per connection,
not cached with the stub.

The first version of this looked right and failed two runs out of two: the call
immediately after a switch threw `UnmarshalException: Error unmarshaling return
header ... Socket closed`. A loop probe that switched transports repeatedly (and
logged the attempt number on each call) localised it. The failure is neither the
tunnel nor the direction of the swap: closing a socket merely starts its
retirement, and until RMI's reader thread notices, the connection is still handed
out. Two endpoints (registry and object) mean up to two such connections.

The fix is a short retry loop in `connect()`: close everything, look up the
registry, and make one warm-up `ping()` so the object endpoint is exercised as
well -- a successful Connect then means both endpoints really answer. 18
transport switches in the probe pass, and a reconnect costs 40 to 100 ms.

---

## 5. Verification

Five gates, each run before moving on:

**G1 — plain RMI works.** Direct mode, no tunnel: lookup, call, image returned.

**G2 — the transport alone is correct.** `lab.tunnel.TunnelProbe` pushes 10 MB
each way through the relay without RMI:

```
bytes: 10.0 MB each way
elapsed: 78 ms
throughput: 128.2 MB/s
integrity: ok, byte-identical
```

**G3 — a real RMI call goes through the tunnel.** With server and relay running:

```
relay        : 127.0.0.1:8080
rmi endpoint : 127.0.0.1:1099  (resolved by the relay)
ping         : ImageLab/25.0.5.0.2
lookup       : 210 ms (two tunnel handshakes: registry + object)
round trip   : 295 ms
server work  : 226 ms
transfer     : 69 ms
integrity    : request OK, response OK
```

and the relay log shows two tunnels (registry, then object):

```
tunnel up    : 127.0.0.1:1099 <- /127.0.0.1:53806
tunnel up    : 127.0.0.1:1100 <- /127.0.0.1:53806
```

**G4 — the tunnel is the only path.** Point the client at a dead relay port while
the RMI server is still listening:

```
java.rmi.ConnectException: Connection refused to host: 127.0.0.1
  at sun.rmi.transport.tcp.TCPEndpoint.newSocket(TCPEndpoint.java:615)
  at sun.rmi.transport.tcp.TCPChannel.createConnection(TCPChannel.java:329)
  at sun.rmi.transport.tcp.TCPChannel.newConnection(TCPChannel.java:217)
  at sun.rmi.server.UnicastRef.newCall(UnicastRef.java:362)
  at sun.rmi.registry.RegistryImpl_Stub.lookup(RegistryImpl_Stub.java:131)
```

Reaching the registry directly is impossible while the tunnel is installed, which
is exactly the claim being tested.

**G5 -- structured replies through the tunnel.** The newer calls return objects,
not just images; the same two handshakes appear in the relay log:

```
histogram    : 79 ms, 262144 pixels, mean luma 131.7, peak bin 114
               reply 4096 bytes of int arrays for a 659.5 KB request
palette k=6  : 189 ms, 6 colours kept, 21.5 KB result from a 659.5 KB source
rotate 90    : 193 ms, 659.1 KB result, sha256 request OK, response OK
```

**Framing capture** (`lab.tunnel.WireCapture`, no RMI, hand-checkable):

```
client sends (request head, no body yet):
  | POST /tunnel?target=127.0.0.1:57613 HTTP/1.1\r\n
  | Host: 127.0.0.1:8080\r\n
  | Transfer-Encoding: chunked\r\n
  | \r\n

relay answers, before any body byte arrived:
  | HTTP/1.1 200 OK\r\n
  | Transfer-Encoding: chunked\r\n
  | \r\n

client sends 13 bytes of application data:
  | d\r\n
  | hello, tunnel\r\n

relay sends the echo back, chunk framed:
  | d\r\n
  | hello, tunnel\r\n
```

---

## 6. Measurements

Environment: single machine, loopback, JDK 25 (Microsoft build), Windows.
8 iterations after 3 warm-up iterations; the first (cold-JVM) iteration is
discarded so that the client and server JIT have already run. Every row is
verified byte-identical (`sha256_ok=true`).

Two workloads: **echo** (`byte[]` in, same `byte[]` out — no compute, so the row
measures transport plus serialisation) and **image** (`process` — a grayscale
filter at radius 6, so transport is a small fraction of the total).

### 6.1 Echo — pure transport

| Payload | direct | CONNECT | chunked | chunked / direct |
| --- | --- | --- | --- | --- |
| 64 KB | 0 ms | 0 ms | 0 ms | below timer resolution |
| 512 KB | 8 ms | 2 ms | 4 ms | 0.5x |
| 4 MB | 19 ms | 20 ms | 38 ms | **2.0x** |

At 4 MB, chunked framing doubles transport time (19 -> 38 ms). CONNECT is
indistinguishable from direct, which is the expected result: it *is* a raw pipe
after 40 bytes of handshake.

### 6.2 Image workload — transport inside a real call

| Payload | Mode | Total | Server work | Transfer | Throughput |
| --- | --- | --- | --- | --- | --- |
| 675 KB | direct | 81 ms | 78 ms | 3 ms | 15.9 MB/s |
| | chunked | 67 ms | 57 ms | 10 ms | 19.2 MB/s |
| | CONNECT | 61 ms | 58 ms | 3 ms | 21.1 MB/s |
| 2.4 MB | direct | 253 ms | 238 ms | 15 ms | 18.8 MB/s |
| | chunked | 228 ms | 204 ms | 24 ms | 20.9 MB/s |
| | CONNECT | 216 ms | 202 ms | 14 ms | 22.0 MB/s |
| 9.1 MB | direct | 915 ms | 871 ms | 44 ms | 18.9 MB/s |
| | chunked | 858 ms | 760 ms | 98 ms | 20.2 MB/s |
| | CONNECT | 797 ms | 756 ms | 41 ms | 21.7 MB/s |

### 6.3 Discussion

**The framing cost is real but bounded.** Transfer nearly doubles under chunked
(44 -> 98 ms at 9 MB), consistent with the echo rows: each 32 KB block gains a
length line and a CRLF, and the codec flushes per write. It stays under the
cost of the round-trip latency that the tunnel removes relative to nothing.

**The end-to-end penalty is much smaller than the transfer penalty.** Total time
for the largest image is 858 ms chunked versus 915 ms direct — inside noise, and
actually lower here, because the server-side compute time varied more between runs
(760 vs 871 ms) than the tunnel added. That variance is the honest headline: on
this workload, compute dominates transport by roughly 8:1, so tunnelling RMI
through HTTP is a perfectly reasonable engineering choice for coarse-grained
services and a bad one for chatty ones.

**Don't compare cold numbers.** An earlier direct run took 227 ms of server time
for what later took 79 ms — same code, cold JVM. Every number here is warm, and
`BenchMain` enforces warm-up iterations for that reason.

**Loopback is not a network.** All rows above have no real network hop, so they
measure the tunnel's own cost, not its behaviour on a link with delay. The matrix
is parameterised by `--network` so the same command produces the LAN and Tailscale
rows; those are added when the second machine is available.

---

## 7. Limitations

* **No TLS.** The tunnel is plaintext HTTP. A real deployment puts the relay behind
  an HTTPS terminator; that changes nothing in the codec, since the codec only
  needs two byte streams.
* **No authentication.** Anyone who can reach :8080 and satisfy the allowlist can
  use the tunnel. The loopback bind limits the target, not the caller.
* **One RMI connection per tunnel, one HTTP request per connection.** RMI reuses
  TCP connections, so this is efficient in practice, but a fresh RMI connection
  pays the HTTP handshake (`lookup` shows ~200 ms for two handshakes).
* **Chunked framing is slower than a raw pipe.** Measured above: ~2x on pure
  transport. A production design would batch smaller chunks or use a binary
  length prefix inside one long chunk; I kept per-write chunks for
  reproducibility.
* **The relay buffers nothing, so a slow reader back-pressures the server.** That
  is correct behaviour for a tunnel, and the chaos knob `buffer=` exists to show
  what a buffering proxy does to it.

## 8. What I would do next

1. Add the LAN/Tailscale rows to §6 with `--network lan` and `--network tailscale`.
2. Add a second relay hop (relay -> relay) to model a chain of two proxies.
3. Put the relay behind an actual HTTP proxy (Squid/nginx) and record which of the
   two forms survives it — the assignment's real-world question.
4. Replace the per-write chunk with a coalescing writer that still flushes on a
   timer, and measure the tradeoff against the deadlock constraint in §4.1.

---

## Appendix A — reproduction

```
mvn -o clean compile

rem terminal 1
cmd /c dist\run-server.cmd
rem terminal 2
cmd /c dist\run-relay.cmd --port 8080
rem terminal 3
cmd /c dist\run-cli.cmd --relay 127.0.0.1:8080 --size 512 --filter sobel

rem framing proof
java -cp target/classes lab.tunnel.WireCapture 127.0.0.1 8080

rem matrix -> bench/results.csv
java --module-path dist/lib --add-modules javafx.controls -cp "dist/app;dist/lib/*" \
  lab.bench.BenchMain --mode chunked --relay 127.0.0.1:8080 --workload echo
```

## Appendix B — the risk that nearly sank it

`RMISocketFactory.setSocketFactory` latches per JVM. Switching transport inside a
running UI is impossible, and an earlier version that let the user try it hung in a
confusing way. The current UI disables the selector after connecting and says so;
`LabClient` refuses a second, different factory with an explicit message. In the
benchmark this is solved with one child JVM per mode — a small design decision
that came directly from an RMI implementation detail, which is the kind of thing
this project was supposed to teach.
