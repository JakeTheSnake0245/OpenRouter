# OpenRoute

OpenRoute is the Tailscale Android client with MeshRoute's split-tunnel mesh
routing built in, for phones that join an OpenMANET (or other WAN-less MANET)
radio while keeping cellular/Wi-Fi for the internet.

Android allows only one VPN at a time, so MeshRoute and Tailscale could not
run together. OpenRoute is one VPN that carries both:

| Traffic | Goes |
|---|---|
| Tailnet (100.64.0.0/10, subnet routes, MagicDNS) | WireGuard, via your Headscale tailnet |
| Mesh subnet (auto-detected, e.g. OpenMANET `10.41.0.0/16`) | Out the radio link (USB-C ethernet or Wi-Fi) |
| Everything else | Untouched — cellular or Wi-Fi as usual |
| Tailnet peers that are also on the mesh | Directly over the radio (Tailscale-over-mesh) |

It installs as its own app (`com.openmanet.openroute`) next to the stock
Tailscale app, and replaces MeshRoute.

## How it works

- **Mesh routing** (`libtailscale/meshroute`). While a mesh link is up, its
  subnets are added to the VPN routes. Packets for them are pulled out of the
  TUN before WireGuard sees them (`meshroute.TUN`) and handed to a gVisor
  userspace stack that terminates each TCP/UDP flow and relays it over a
  socket bound to the mesh `Network` (`stack.go`). Pings use an unprivileged
  ICMP socket bound to the mesh (`ping_linux.go`). This replaces MeshRoute's
  hand-written TCP/UDP/ICMP relays.
- **Tailscale over the mesh** (`meshroute/conn.go`). magicsock's UDP socket is
  bound to the internet network, so it can't reach peers' mesh addresses.
  While the mesh is up, it is paired with a second socket bound to the mesh;
  writes to mesh addresses use it and reads come from both. The phone's mesh
  address is advertised as a local endpoint, so peers on the radio find each
  other through disco like on a LAN. This needs one small patch to
  `tailscale.com` (`patches/tailscale.com/`), applied at build time by
  `scripts/prepare-tailscale-fork.sh` into `third_party/` (via the `replace`
  in `go.mod`).
- **Link detection** (`android/.../mesh/MeshRouter.kt`) is a port of
  MeshRoute's: Ethernet, a designated Wi-Fi network (for static-IP radios),
  or a radio hotspot the app joins itself as a local-only network.
- **Internet rescue** ("Keep internet on cellular" switch, on by default).
  Tailscale's own traffic always uses the best validated internet network, so
  TAK, Predator RF, and admin panels over the tailnet keep working even if the
  radio takes over Android's default network. The switch only matters for
  other apps: when the mesh link holds the default network without working
  internet, OpenRoute routes the IPv4 internet through the VPN and relays it
  over cellular. It steps aside when a tailnet route (including an exit node)
  covers the destination.
- **Offline field start.** If Tailscale has no addresses yet (no internet at
  boot), the VPN still comes up with a placeholder address (192.0.0.8) so mesh
  routing works.

Exit-node controls are hidden (use the standalone Tailscale app for exit
nodes) unless an MDM policy explicitly shows them.

## Using it

1. Install `OpenRoute-<version>.apk` (sideload).
2. Tap **Use your organization's server**, enter the Headscale URL once, and
   sign in through your SSO in the browser. (MDM can preset `LoginURL`.)
3. Tap the **Mesh** card on the main screen: choose the link mode, and
   optionally a mesh host for the watchdog / Test mesh.
4. Recommended: Android Settings → VPN → OpenRoute → **Always-on VPN**, and
   battery usage **Unrestricted**.

## Building

Requirements (installed by the setup used for 1.0.0): JDK 17
(`brew install openjdk@17`), Android SDK platform 36, build-tools 36.0.0,
NDK 23.1.7779620. The Go toolchain is fetched by `tool/go`.

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
make openroute-apk          # signed release APK → OpenRoute-<version>.apk
make apk                    # debug APK
./tool/go test ./libtailscale/meshroute   # mesh routing tests (run on any OS)
```

`make openroute-apk` needs `android/keystore.properties` (not in git):

```
storeFile=/path/to/openroute-release.jks
storePassword=...
keyAlias=openroute
keyPassword=...
```

**Keep the keystore safe.** Phones only accept updates signed with the same
key; if it's lost, every device has to uninstall and reinstall.

Bump `openRouteVersion` in `android/build.gradle.kts` for each release.

## Field test checklist

- [ ] USB-C ethernet to a node: Mesh card shows `Mesh: 10.41.0.0/16`; ping and
      open a node's web UI by IP while LTE browsing still works.
- [ ] Test mesh → "mesh link healthy"; Find devices lists nodes.
- [ ] Tailnet services (TAK, Predator RF, admin panels) reachable at the same
      time.
- [ ] Two OpenRoute phones (or a phone and a tailnet laptop) on the same mesh:
      `tailscale ping` / the peer details show a direct `10.41.x.x` path.
- [ ] Static-IP radio Wi-Fi as default network: browser still works over
      cellular with "Keep internet on cellular" on; Mesh status shows
      "Cellular (rescue)".
- [ ] Unplug the radio: routes drop within a few seconds; replug: they return.
- [ ] Airplane mode + radio only, reboot: mesh routing still comes up.

## Known limits

- IPv4 only on the mesh (as with MeshRoute); multicast (e.g. ATAK SA) stays
  native on the radio interface and is not routed through the VPN.
- UDP broadcast to the mesh isn't relayed.
- Mesh routing applies while OpenRoute is connected (VPN on).
- Mesh nodes' own DNS (e.g. a node's dnsmasq at 10.41.x.1) isn't used:
  Tailscale's DNS forwarder sockets are bound to the internet network, so a
  Headscale split-DNS route to a mesh resolver won't answer. Use IPs, or add
  the nodes as Headscale `dns.extra_records`, which MagicDNS answers locally.
