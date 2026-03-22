# Integrating PocketScope with an Existing INDI Setup

PocketScope runs a standard INDI server on port 7624. No special mode or protocol
extension is needed to add it to an existing rig — INDI's built-in **server chaining**
handles everything.

## Device Names

PocketScope advertises these INDI device names (must match exactly when chaining):

| Device | Name Pattern | Example |
|--------|-------------|---------|
| Camera (wide) | `PocketScope Ultrawide` | focal length < 3mm |
| Camera (main) | `PocketScope Main` | focal length 3–8mm |
| Camera (tele) | `PocketScope Tele` | focal length > 8mm |
| Focuser | `PocketScope Focuser` | software focus control |

Which cameras appear depends on the phone hardware. A Pixel 6 Pro, for example,
exposes all three lenses — each as a separate CCD device.

## Setup Scenarios

### Scenario 1: Add PocketScope to a Local indiserver

You already run `indiserver` locally (e.g., on a laptop) with your mount, guide camera,
etc. Add the phone's devices by chaining:

```bash
# Phone is at 192.168.1.100, running PocketScope on port 7624
indiserver indi_eqmod_telescope indi_asi_ccd \
  "PocketScope Main"@192.168.1.100:7624 \
  "PocketScope Focuser"@192.168.1.100:7624
```

Ekos connects to your local indiserver as usual and sees PocketScope's cameras
alongside your existing equipment.

### Scenario 2: Add PocketScope to a Remote Astro PC

You run KStars on your desktop and connect to a remote indiserver on an astro mini PC
(e.g., Raspberry Pi, StellarMate) attached to your rig. Chain PocketScope's devices
into that remote server:

```
┌──────────┐         ┌──────────────┐         ┌────────────┐
│  KStars  │──WiFi──▶│  Astro PC    │◀──WiFi──│  Phone     │
│ (Desktop)│         │  indiserver  │         │ PocketScope│
│          │         │  :7624       │         │  :7624     │
└──────────┘         └──────────────┘         └────────────┘
```

On the astro PC:

```bash
indiserver indi_eqmod_telescope indi_asi_ccd indi_robo_focus \
  "PocketScope Main"@phone-ip:7624 \
  "PocketScope Tele"@phone-ip:7624
```

KStars connects to the astro PC's indiserver. All devices — mount, dedicated CCD,
focuser, and PocketScope cameras — appear in a single Ekos profile.

### Scenario 3: Standalone (PocketScope Only)

Point KStars/Ekos directly at the phone's IP on port 7624. No chaining needed.
This is the default mode — PocketScope is the entire observatory.

## Ekos Profile Setup

Ekos connects to **one** INDI server per profile. When chaining, all devices appear
on that single server, so no special Ekos configuration is needed beyond the standard
remote connection settings:

1. Open Ekos Profile Editor
2. Set **Mode** to `Remote`
3. Set **Host** to your indiserver's IP (local machine or astro PC)
4. Set **Port** to `7624`
5. Select PocketScope devices from the dropdown lists (CCD, Focuser, etc.)

## INDI Web Manager

If you use INDI Web Manager on your astro PC, add PocketScope as remote drivers
in your profile using the `device@host:port` syntax. The Web Manager passes these
through to `indiserver` on startup.

## How Chaining Works

INDI's `indiserver` can act as both a server (to clients) and a client (to other
servers) simultaneously. When you specify `"Device Name"@host:port`, the server:

1. Connects to the remote indiserver as a client
2. Subscribes to the named device
3. Proxies all property definitions, updates, and BLOBs to local clients

From the client's perspective, remote and local devices are indistinguishable.
Image BLOBs traverse the network only once (phone → aggregating server → client).

## Notes

- PocketScope must be running **before** starting the chaining indiserver
- Device names are **case-sensitive** and must match exactly
- The phone and astro PC must be on the same network (or have routable IPs)
- Port 7624 is the INDI default; PocketScope uses this unless changed in settings
- No changes to PocketScope are needed — standard INDI protocol handles everything
