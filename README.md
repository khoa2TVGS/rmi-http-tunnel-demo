# ImageLab — RMI over an HTTP tunnel

A remote image-processing lab: a JavaFX client calls an RMI service that filters
images, and every RMI byte travels inside an HTTP/1.1 request instead of a raw TCP
connection. The tunnel is written from scratch — the JDK removed its own
RMI-over-HTTP socket factories long ago, so there is nothing to switch on.

```
client (JavaFX) --HTTP :8080--> relay --plain TCP--> RMI server
                                  |                  :1099 registry
                                  |                  :1100 exported object
                                  +-- one HTTP request per RMI connection
```

The relay is a real HTTP server: it speaks the protocol (request line, headers,
chunked framing, CONNECT), so any proxy that permits a long-lived POST or a
CONNECT will carry the tunnel.

## Requirements

* JDK 21 or newer (built and tested with JDK 25).
* Windows for the `.cmd` launchers; the Java code itself is portable.

## Run it

Three terminals, in this order:

```
dist\run-server.cmd       # RMI on 127.0.0.1:1099 and :1100
dist\run-relay.cmd        # HTTP on 0.0.0.0:8080
dist\run-ui.cmd           # the JavaFX client
```

In the client: transport `Tunnel (chunked)`, relay `127.0.0.1:8080`, press Connect.
`Tunnel (CONNECT)` is the same tunnel in its other HTTP form, and `Direct` skips
the relay entirely for a baseline. **Disconnect** closes the tunnel's sockets and
unlocks the bar, so you can switch transport without restarting the app. Every
round trip reports its timings and a SHA-256 check of both directions.

The same path from the command line, no window:

```
dist\run-cli.cmd --relay 127.0.0.1:8080 --size 1024 --filter blur --radius 6
dist\run-cli.cmd --relay 127.0.0.1:8080 --size 512 --op histogram
dist\run-cli.cmd --relay 127.0.0.1:8080 --size 512 --op palette --colors 6
dist\stop-all.cmd        # stop whatever is holding a port
```

## Run it across machines (LAN or Tailscale)

The client only ever dials the relay. The RMI endpoint name (`127.0.0.1:1099`) is
resolved *by the relay*, in the relay's network namespace, so it stays `127.0.0.1`
in every deployment.

1. On the server machine, start the server and the relay.
2. On the client machine run `dist\run-ui.cmd`, pick `Tunnel (chunked)`, and set the
   relay to `<server-ip>:8080` — the LAN address (`192.168.x.x`) or the Tailscale
   address (`100.x.x.x`).
3. Press Connect.

If it hangs, a firewall is dropping inbound TCP 8080 on the server machine.
Nothing else needs to be opened: ports 1099 and 1100 are bound to loopback only,
so they are unreachable from outside by construction. Confirm with
`netstat -ano | findstr :1099`.

## Why the ports look like that

| Port | Bound to | Who may connect |
| --- | --- | --- |
| 1099 registry | 127.0.0.1 | the relay, same machine |
| 1100 object | 127.0.0.1 | the relay, same machine |
| 8080 relay | 0.0.0.0 | whoever you allow |

Binding RMI to loopback is the entire access-control story: there is no firewall
rule to forget, and the tunnel cannot be bypassed. The relay's `--allow` flag
restricts which targets it will dial (default: loopback only; `*` allows anything).
An open relay would let anyone who reaches :8080 reach anything the relay can,
which is why the default is closed.

## The UI

One connection bar at the top, one tab per tool. Connect installs the selected
transport and locks the bar; Disconnect closes the tunnel's sockets and unlocks
it. The socket factory itself can only be installed once per JVM, so it is
installed through a switchable wrapper that is retargeted instead of replaced.

* **Image filter** — pick a synthetic image, choose a filter (grayscale,
  posterize, sobel, blur, resize), press *Run*. Shows both images, the timings
  (round trip, server work, transfer) and the integrity verdict.
* **Transform** — rotate 90/180/270, flip, or crop with a margin slider. These
  ride the existing `transform` call; no new remote method was needed.
* **Histogram** — the service returns 256-bin arrays for red, green, blue and
  luma; the tab draws them and reports mean luma and the peak bin.
* **Palette** — k-means colour quantisation with 2 to 32 colours, plus the
  returned palette drawn as swatches.
* **Benchmark** — runs the measurement matrix by spawning one child JVM per
  (workload, mode) pair, because of that same one-shot latch. Results land in
  `bench/results.csv` and in the chart and table.
* **Tunnel inspector** — the transport with RMI taken out of the picture.
  *Run probe* pumps N MB through the relay to a throw-away echo server and
  verifies SHA-256; *Capture handshake* prints the bytes of one handshake. Both
  honour the toolbar's chaos field (`buffer=N`, `delay=N` ms, `drop=N` bytes), so
  you can watch what a buffering proxy does to a call.

![Image filter](doc/ui-sample.png)
![Transform](doc/ui-transform.png)
![Histogram](doc/ui-histogram.png)
![Palette](doc/ui-palette.png)

## Command line tools

| Class | Purpose |
| --- | --- |
| `lab.client.TunnelClientMain` | one image round trip through the tunnel |
| `lab.client.DirectClientMain` | the same, without the tunnel (baseline) |
| `lab.bench.BenchMain` | measurement matrix -> CSV |
| `lab.tunnel.TunnelProbe` | raw byte pump through the relay, no RMI |
| `lab.tunnel.WireCapture` | prints the bytes of one handshake |
| `lab.server.ServerMain` | the RMI server |
| `lab.relay.RelayMain` | the HTTP relay |

```
java --module-path dist/lib --add-modules javafx.controls -cp "dist/app;dist/lib/*" \
  lab.bench.BenchMain --mode chunked --relay 127.0.0.1:8080 \
  --workload image --iterations 8 --network lan --out bench/results.csv
```

## Building

```
mvn -o clean compile     # compile only
make-dist.cmd            # rebuild dist/ (jars + launchers)
```

## Troubleshooting

| Symptom | Cause |
| --- | --- |
| `Port already in use: 1100` | an old server is still running: `dist\stop-all.cmd` |
| `Address already in use: bind` (relay) | same, for 8080 |
| `UnmarshalException ... Socket closed` right after Disconnect | RMI still holds the connection it just closed; Connect retries for ~160 ms. A persistent failure means the relay or the server is down. |
| Connect hangs, no relay log line | the request never arrived: wrong IP, or a firewall on 8080 |
| Connect hangs, relay log line present | the relay dialled the target and got nothing: is the RMI server up? |
| `ClassNotFoundException` / serialVersionUID mismatch | client and server built from different trees; rebuild both |

Details, measurements and the design rationale are in [REPORT.md](REPORT.md).
