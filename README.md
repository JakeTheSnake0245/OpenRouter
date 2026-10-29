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

## Download

Get the signed APK from [Releases](../../releases/latest) and sideload it
(Android 8+; arm64, arm, x86 and x86_64 in one APK). Updates install over the
top as long as they come from this repo's releases (same signing key).

Before installing on a new phone, check the download against the SHA-256 in
the release notes, and the signing certificate against this fingerprint
(`apksigner verify --print-certs OpenRoute-<version>.apk`):

```
a6b6ef03e61ff7d06a11386dc685e793a1c5aba6a2f9f926dd5bfe63441c990a
```

Upstream Tailscale's README is kept as [README-tailscale.md](README-tailscale.md).

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
- **Offline field start.** OpenRoute keeps the last tailnet map on disk
  (Tailscale's netmap cache, which Headscale doesn't switch on, so OpenRoute
  forces it). Starting with no internet — radio only — the app comes up
  from that map: the VPN starts, mesh routing works, and tailnet peers that
  are also on the mesh stay reachable directly. The Tailnet tile shows
  "Server offline" until Headscale is reachable again. With no cached map
  (first run), the VPN still comes up with a placeholder address (192.0.0.8)
  once started, so mesh routing works. Safeguards:
  - The map is encrypted with AES-256-GCM under a key kept in the app's
    Keystore-backed encrypted preferences (`libtailscale/netmapcrypt.go`).
  - It is only used for **72 hours** after Headscale last sent an update, so a
    device removed on the server (say, a lost phone) drops off even phones
    that restart offline within that time.
  - Mesh → **Offline tailnet map** turns it off (and deletes it) per phone,
    and **Wipe saved map** deletes it now. A control-server
    `disable-cache-network-maps` attribute also turns it off.

## Security

- **Tailnet traffic never goes to the mesh unencrypted.** Tailscale's range
  (100.64.0.0/10, including MagicDNS at 100.100.100.100) is never diverted
  to the radio, and mesh subnets that overlap it, or are wider than /8, are
  refused.
- **The radio can't choose what gets routed to it.** In auto mode the link's
  subnet is routed only where it falls inside the configured **mesh ranges**
  (default `10.41.0.0/16`); a radio or network handing out anything else is
  shown as an error and not routed.
- **Wi-Fi network mode adopts a network only by name** (or, while Android
  hides the name in the background, by matching the subnet it had last time,
  inside the mesh ranges). Android only reveals Wi-Fi names with location
  permission and location turned on. Radio hotspot mode (name + password)
  or USB ethernet are stronger.
- **No logs go to Tailscale.** Remote diagnostic logging is off by default
  (Settings can turn it on). The radio hotspot password is stored encrypted.
- **Other apps can't control the VPN.** The connect/disconnect/exit-node
  intents require a signature permission, so only apps signed with the
  OpenRoute key can use them.
- Headscale's DERP map decides which relay servers carry traffic when a
  direct path isn't possible; to keep relayed (still encrypted) traffic off
  Tailscale's public DERP servers, run Headscale's embedded DERP and drop
  the default map.

Exit-node controls are hidden (use the standalone Tailscale app for exit
nodes) unless an MDM policy explicitly shows them.

## Main screen

The main screen is a status board with one tile per link: **Tailnet**
(secure / devices online), **Mesh** (linked, no link, degraded) and
**Internet** (which network apps are using, and whether rescue is carrying
them). Tap a tile for details.

Other tailnet devices aren't listed on the main screen, and the control
server's hostname isn't shown there either. The device list is behind the
Tailnet tile and shows names and online state only. A device's addresses are
masked on its details screen until you tap **Show**; tapping a row copies
the real address either way.

## Using it

1. Install `OpenRoute-<version>.apk` (sideload).
2. Tap **Use your organization's server**, enter the Headscale URL once, and
   sign in through your SSO in the browser. (MDM can preset `LoginURL`.)
3. Tap the **Mesh** tile on the main screen: choose the link mode, and
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

- [ ] USB-C ethernet to a node: Mesh tile shows `LINKED` and `10.41.0.0/16`; ping and
      open a node's web UI by IP while LTE browsing still works.
- [ ] Test mesh → "mesh link healthy"; Find devices lists nodes.
- [ ] Tailnet services (TAK, Predator RF, admin panels) reachable at the same
      time.
- [ ] Two OpenRoute phones (or a phone and a tailnet laptop) on the same mesh:
      `tailscale ping` / the peer details show a direct `10.41.x.x` path.
- [ ] Static-IP radio Wi-Fi as default network: browser still works over
      cellular with "Keep internet on cellular" on; Mesh status shows
      "Cellular (rescue)" and the Internet tile says it's carrying app traffic.
- [ ] Unplug the radio: routes drop within a few seconds; replug: they return.
- [ ] Airplane mode + radio only, reboot: mesh routing still comes up; the
      Tailnet tile shows "Server offline"; turning internet back on returns it
      to "Secure".
- [ ] A network whose subnet is outside the mesh ranges (e.g. a phone
      hotspot in Wi-Fi network mode) is shown as "outside the mesh ranges"
      and nothing is routed to it.

## Known limits

- IPv4 only on the mesh (as with MeshRoute); multicast (e.g. ATAK SA) stays
  native on the radio interface and is not routed through the VPN.
- UDP broadcast to the mesh isn't relayed.
- Mesh routing applies while OpenRoute is connected (VPN on).
- Mesh nodes' own DNS (e.g. a node's dnsmasq at 10.41.x.1) isn't used:
  Tailscale's DNS forwarder sockets are bound to the internet network, so a
  Headscale split-DNS route to a mesh resolver won't answer. Use IPs, or add
  the nodes as Headscale `dns.extra_records`, which MagicDNS answers locally.
