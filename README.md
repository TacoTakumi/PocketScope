# PocketScope

Turn your Android phone into a fully-featured INDI astronomy camera and mount controller. PocketScope connects to any INDI client over WiFi and presents your phone's cameras as real CCD devices — indistinguishable from dedicated astronomy hardware.

**Free. Open source. No extra hardware required.**

## Why PocketScope?

Modern smartphones have exceptional camera sensors, multi-lens systems, and motorized focus — everything needed for astrophotography. PocketScope exposes all of this through the standard INDI protocol, so your phone works seamlessly with the same software used by dedicated astronomy setups.

| | PocketScope | Dedicated Camera | Stellarmate/Pi | Smart Telescope |
|---|---|---|---|---|
| **Cost** | Free | $300–$1500+ | ~$150 | $450+ |
| **RAW Bayer Data** | Yes | Yes | Yes | No |
| **INDI Compatible** | Yes | Yes | Yes | No |
| **Multi-Lens** | Yes (3 lenses) | No | No | No |
| **Built-in Focuser** | Yes | External needed | External needed | Yes |
| **Open Source** | Yes | No | Partial | No |

## Features

- **Three CCD Devices** — ultrawide, main, and telephoto lenses each appear as separate INDI cameras with correct pixel scale and sensor metadata
- **INDI Focuser** — motorized lens focus controllable from Ekos auto-focus or manually via INDI properties
- **16-bit Raw Bayer FITS** — unprocessed sensor data with accurate astronomy headers (BAYERPAT, XPIXSZ, YPIXSZ, EXPTIME, GAIN), ready for calibration and stacking
- **Full Manual Control** — exposure time (nanoseconds to 300+ seconds), ISO/gain (50–12800+), white balance, and focus
- **Night Vision UI** — red-tinted interface preserves dark adaptation
- **Background Operation** — Android foreground service keeps the INDI server alive with wake lock
- **Memory-Safe Streaming** — Base64 BLOB encoding streams directly to the TCP socket, avoiding out-of-memory crashes on large images
- **No On-Device Debayering** — raw linear data preserved for proper dark/flat calibration in your processing pipeline

## Requirements

**Phone:**
- Android 10+ (API 29)
- Rear camera(s) with RAW_SENSOR support (most modern flagships)

**Desktop — any INDI client, for example:**
- [KStars/Ekos](https://edu.kde.org/kstars/) — full-featured planetarium with capture, focusing, and guiding
- [CCDciel](https://www.ap-i.net/ccdciel/) — lightweight capture and observatory control
- [PHD2](https://openphdguiding.org/) — autoguiding (connects to PocketScope as a guide camera)
- [INDI Web Manager](https://www.indilib.org/support/tutorials/162-indi-web-manager.html) — browser-based device management
- [`tests/indi_test_client.py`](tests/indi_test_client.py) — included Python test script for quick verification without a full client
- Same WiFi network as the phone (or phone hotspot for field use)

**Verified on:** Google Pixel 6 Pro — Samsung GN1 (main, 50MP, ~2.5"/px), Sony IMX481 (ultrawide, 12MP, ~7–8"/px), Samsung GM1 (telephoto 4x, 48MP, ~0.8"/px)

## Build & Install

```bash
git clone https://github.com/TacoTakumi/PocketScope.git
cd PocketScope
```

Open in Android Studio and build, or from the command line:

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

**Dependencies** (handled by Gradle):
- Ktor Network 3.4.1
- nom.tam.fits 1.21.2
- Kotlinx Coroutines 1.10.2
- Jetpack Compose (BOM 2026.03.00)

## Quick Start

1. **Launch PocketScope** on your phone — the server starts automatically and displays the IP address and port (7624)
2. **Connect any INDI client** — enter the phone's IP and port 7624 (e.g., in Ekos: create a profile with Mode set to "Remote")
3. **Discover devices** — the client finds the camera and focuser devices
4. **Capture** — select a lens, set exposure/gain, and start imaging

**Quick test without a full client:**
```bash
# pyindi-client requires libindi-dev to build its native bindings
sudo apt install libindi-dev    # Debian/Ubuntu
pip install -r tests/requirements.txt

python3 tests/indi_test_client.py --host <phone-ip>
```

## INDI Device Names

| Device | Type | Description |
|---|---|---|
| `PocketScope Ultrawide` | CCD | Wide-angle lens (~7–8"/px on Pixel 6 Pro) |
| `PocketScope Main` | CCD | Primary lens (~2.5"/px on Pixel 6 Pro) |
| `PocketScope Tele` | CCD | Telephoto lens (~0.8"/px on Pixel 6 Pro) |
| `PocketScope Focuser` | Focuser | Motorized lens focus control |

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
2. Start PocketScope — server binds to port 7624
3. Connect Ekos to phone IP
4. Select lens (Ultrawide for framing, Main or Tele for detail)
5. Set exposure and gain in Ekos
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
├── camera/            # Camera2 integration, RAW capture, lens enumeration
├── imaging/           # DNG-to-FITS conversion, Bayer patterns
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
| nom.tam.fits 1.21.2 | FITS file generation with astronomy headers |
| XmlPullParser | Streaming INDI XML parsing over persistent TCP |
| Jetpack Compose | Night-vision UI |

## Why Not the Front Camera?

Front cameras lack RAW_SENSOR output, have fixed focus (can't reach infinity), and smaller sensors with narrower apertures. See [Doc/why-not-front-camera.md](Doc/why-not-front-camera.md) for the full analysis.

## License

PocketScope is free software released under the [GNU General Public License v3](LICENSE), aligning with the INDI/KStars astronomy ecosystem.

## Contributing

Contributions welcome. PocketScope uses standard INDI protocol — if you're familiar with INDI driver development, you'll feel at home.

File issues and pull requests on GitHub.
