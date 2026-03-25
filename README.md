# PocketScope

Turn your Android phone into a fully-featured astronomy camera and controller. PocketScope connects to INDI and ASCOM Alpaca clients over WiFi, presenting your phone's cameras as real CCD devices — indistinguishable from dedicated astronomy hardware.

**Free. Open source. No extra hardware required.**

## Why PocketScope?

Modern smartphones have exceptional camera sensors, multi-lens systems, and motorized focus — everything needed for astrophotography. PocketScope exposes all of this through both the INDI and ASCOM Alpaca protocols, so your phone works seamlessly with the same software used by dedicated astronomy setups.

| | PocketScope | Dedicated Camera | Stellarmate/Pi | Smart Telescope |
|---|---|---|---|---|
| **Cost** | Free | $300–$1500+ | ~$150 | $450+ |
| **RAW Bayer Data** | Yes | Yes | Yes | No |
| **INDI Compatible** | Yes | Yes | Yes | No |
| **ASCOM Alpaca** | Yes | Some | No | No |
| **Multi-Lens** | Yes (3 lenses) | No | No | No |
| **Built-in Focuser** | Yes | External needed | External needed | Yes |
| **Open Source** | Yes | No | Partial | No |

## Features

- **Two Protocols** — INDI (TCP port 7624) and ASCOM Alpaca (HTTP port 11111) run simultaneously, so any astronomy client can connect
- **Every Lens is a Camera** — each rear lens is automatically detected and exposed as a separate camera device with correct pixel scale and sensor metadata (3 on Pixel 6 Pro, varies by phone)
- **INDI Focuser** — motorized lens focus controllable from Ekos auto-focus or manually via INDI properties
- **16-bit Raw Bayer FITS** — unprocessed sensor data with accurate astronomy headers (BAYERPAT, XPIXSZ, YPIXSZ, EXPTIME, GAIN), ready for calibration and stacking
- **Full Manual Control** — exposure time (nanoseconds to 300+ seconds), ISO/gain (50–12800+), white balance, and focus
- **Alpaca Discovery** — UDP broadcast on port 32227 lets Alpaca clients auto-discover PocketScope on the network
- **Network Security** — private-network IP filtering and per-client approval prompts for both protocols
- **Night Vision UI** — red-tinted interface preserves dark adaptation
- **Background Operation** — Android foreground service keeps both servers alive with wake lock
- **Memory-Safe Streaming** — Base64 BLOB encoding streams directly to the TCP socket, avoiding out-of-memory crashes on large images
- **No On-Device Debayering** — raw linear data preserved for proper dark/flat calibration in your processing pipeline

## Requirements

**Phone:**
- Android 10+ (API 29)
- Rear camera(s) with RAW_SENSOR support (most modern flagships)
- **iOS is not supported** — Apple does not expose unprocessed linear Bayer sensor data to third-party apps. Astronomy calibration (darks, flats, bias) requires raw data before any computational processing. If Apple opens this access in the future, an iOS port becomes feasible.

**Desktop — any INDI or ASCOM Alpaca client, for example:**

INDI clients:
- [KStars/Ekos](https://edu.kde.org/kstars/) — full-featured planetarium with capture, focusing, and guiding
- [CCDciel](https://www.ap-i.net/ccdciel/) — lightweight capture and observatory control
- [PHD2](https://openphdguiding.org/) — autoguiding (connects to PocketScope as a guide camera)
- [INDI Web Manager](https://www.indilib.org/support/tutorials/162-indi-web-manager.html) — browser-based device management

ASCOM Alpaca clients:
- [N.I.N.A.](https://nighttime-imaging.eu/) — Windows astrophotography suite with native Alpaca support
- [SGPro](https://www.sequencegeneratorpro.com/) — Windows capture sequencer
- [alpyca](https://pypi.org/project/alpyca/) — Python library for scripting against Alpaca devices

Test scripts (included):
- [`tests/indi_test_client.py`](tests/indi_test_client.py) — INDI protocol test script
- Same WiFi network as the phone (or phone hotspot for field use)

**Verified on:** Google Pixel 6 Pro — Samsung GN1 (main, 50MP, ~2.5"/px), Sony IMX481 (ultrawide, 12MP, ~7–8"/px), Samsung GM1 (telephoto 4x, 48MP, ~0.8"/px)

## Alpaca Support — Testing Wanted

Alpaca server infrastructure is new in v1.1. The management API, device discovery, and common device endpoints are implemented and verified. **Camera and focuser-specific Alpaca endpoints are coming in the next release.**

If you have N.I.N.A., SGPro, or another Alpaca client, testing is very welcome:

```bash
# Auto-discover PocketScope on your network
python3 -c "
import socket
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
s.sendto(b'alpacadiscovery1', ('255.255.255.255', 32227))
s.settimeout(5)
print(s.recvfrom(1024))
"

# Or with alpyca (pip install alpyca)
python3 -c "from alpaca.discovery import search_ipv4; print(search_ipv4())"

# Query the management API
curl http://<phone-ip>:11111/management/apiversions
curl http://<phone-ip>:11111/management/v1/description
curl http://<phone-ip>:11111/management/v1/configureddevices

# Query device endpoints
curl http://<phone-ip>:11111/api/v1/camera/0/name
curl http://<phone-ip>:11111/api/v1/camera/0/connected
curl http://<phone-ip>:11111/api/v1/focuser/0/interfaceversion
```

Please [open an issue](https://github.com/TacoTakumi/PocketScope/issues) with your results.

## Install

Download the latest APK from [Releases](https://github.com/TacoTakumi/PocketScope/releases):

1. On your phone, download `PocketScope-<version>.apk` from the latest release
2. Tap the downloaded file to install (you may need to enable "Install from unknown sources" for your browser in Settings > Apps > Special access)
3. Grant camera and location permissions when prompted

## Build from Source

```bash
git clone https://github.com/TacoTakumi/PocketScope.git
cd PocketScope
./gradlew assembleRelease
adb install app/build/outputs/apk/release/PocketScope-*.apk
```

**Dependencies** (handled by Gradle):
- Ktor Network + Server CIO 3.4.1
- nom.tam.fits 1.21.2
- Kotlinx Coroutines 1.10.2
- Kotlinx Serialization JSON 1.8.1
- Jetpack Compose (BOM 2026.03.00)

## Quick Start

1. **Launch PocketScope** on your phone — the server starts automatically and displays the IP address
2. **Connect any client** — INDI clients use port 7624, Alpaca clients use port 11111 (or auto-discover via UDP)
3. **Discover devices** — the client finds the camera and focuser devices
4. **Capture** — select a lens, set exposure/gain, and start imaging

**Quick test without a full client:**
```bash
# INDI test (pyindi-client requires libindi-dev to build its native bindings)
sudo apt install libindi-dev    # Debian/Ubuntu
pip install -r tests/requirements.txt
python3 tests/indi_test_client.py --host <phone-ip>

# Alpaca test (no native dependencies)
pip install alpyca
python3 -c "from alpaca.discovery import search_ipv4; print(search_ipv4())"
```

## Device Names

Devices are detected dynamically from your phone's hardware. Example on a Pixel 6 Pro:

**INDI devices** (TCP port 7624):

| Device | Type | Description |
|---|---|---|
| `PocketScope Ultrawide` | CCD | Wide-angle lens (~7–8"/px) |
| `PocketScope Main` | CCD | Primary lens (~2.5"/px) |
| `PocketScope Tele` | CCD | Telephoto lens (~0.8"/px) |
| `PocketScope Focuser` | Focuser | Motorized lens focus control |

**Alpaca devices** (HTTP port 11111):

| Device | Type | Number | Description |
|---|---|---|---|
| `PocketScope Ultrawide Camera` | Camera | 0 | Wide-angle lens |
| `PocketScope Main Camera` | Camera | 1 | Primary lens |
| `PocketScope Tele Camera` | Camera | 2 | Telephoto lens |
| `PocketScope Focuser` | Focuser | 0 | Motorized lens focus control |

Other phones will show fewer or more cameras depending on their lens configuration.

## Integrating with an Existing INDI Setup

PocketScope speaks standard INDI protocol and supports server chaining. Add its devices to an existing indiserver alongside your mount, filter wheel, or other hardware:

```bash
indiserver indi_eqmod_telescope indi_asi_ccd \
  "PocketScope Main"@192.168.1.100:7624 \
  "PocketScope Focuser"@192.168.1.100:7624
```

Or point Ekos directly at the phone's IP:7624 for standalone use.

See [Doc/indi-remote-integration.md](Doc/indi-remote-integration.md) for detailed chaining scenarios.

## Typical Workflow

1. Mount phone on telescope (or tripod for wide-field)
2. Start PocketScope — INDI server binds to port 7624, Alpaca to port 11111
3. Connect Ekos (INDI) or N.I.N.A. (Alpaca) to phone IP
4. Select lens (Ultrawide for framing, Main or Tele for detail)
5. Set exposure and gain in your client
6. Capture — raw Bayer FITS streams to your desktop
7. Calibrate and stack in Siril, PixInsight, or your preferred tool

## Project Structure

```
app/src/main/kotlin/com/pocketscope/
├── indi/
│   ├── server/        # INDI TCP server, client sessions
│   ├── protocol/      # XML stream parser
│   ├── device/        # CCD and Focuser device implementations
│   └── properties/    # INDI property framework, BLOB handling
├── alpaca/
│   ├── server/        # Alpaca HTTP server, UDP discovery
│   ├── model/         # JSON response models, ASCOM error codes
│   └── device/        # Alpaca Camera and Focuser device wrappers
├── camera/            # Camera2 integration, RAW capture, lens enumeration
├── device/            # Shared device layer (CaptureDevice, FocuserDevice, DeviceRegistry)
├── imaging/           # DNG-to-FITS conversion, Bayer patterns
├── network/           # Network security (IP filter, approval manager)
├── service/           # Foreground service, wake lock management
├── ui/                # Night-vision themed Compose UI
└── MainActivity.kt
```

## Tech Stack

| Technology | Purpose |
|---|---|
| Kotlin 2.0+ | Primary language |
| Android Camera2 API | RAW sensor access and manual controls |
| Ktor Network 3.4.1 | Non-blocking TCP server (INDI port 7624) |
| Ktor Server CIO 3.4.1 | HTTP server (Alpaca port 11111) |
| Kotlinx Serialization | JSON responses for Alpaca protocol |
| nom.tam.fits 1.21.2 | FITS file generation with astronomy headers |
| XmlPullParser | Streaming INDI XML parsing over persistent TCP |
| Jetpack Compose | Night-vision UI |

## Why Not the Front Camera?

Front cameras lack RAW_SENSOR output, have fixed focus (can't reach infinity), and smaller sensors with narrower apertures. See [Doc/why-not-front-camera.md](Doc/why-not-front-camera.md) for the full analysis.

## License

PocketScope is free software released under the [GNU General Public License v3](LICENSE), aligning with the INDI/KStars astronomy ecosystem.

## Contributing

Contributions welcome. PocketScope uses standard INDI and ASCOM Alpaca protocols — if you're familiar with INDI driver development or ASCOM device drivers, you'll feel at home.

File issues and pull requests on GitHub.
