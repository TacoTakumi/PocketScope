# PocketScope — Project Specification
> Open source Android INDI astronomy camera app — Claude Code context document

---

## Vision

Turn an Android phone into a first-class INDI astronomy camera and mount controller. The phone connects to existing astronomy software (KStars/Ekos) over WiFi and appears as a real CCD camera and telescope controller — indistinguishable from dedicated astronomy hardware. Long term: fully standalone observatory with no PC required, and an open hardware motorized mount designed specifically for phones.

---

## The Problem Being Solved

- Dedicated astronomy cameras (ZWO ASI, QHY) cost $300–$1500+
- Existing phone camera apps have no INDI support and no RAW access suitable for astronomy
- Raspberry Pi Stellarmate/Astroberry setups (~$80–150) are popular but require separate hardware
- Smart telescope alternatives (Dwarf II, Vespera) are closed ecosystem, expensive, no RAW access
- Modern phone cameras (especially Pixel 6 Pro) have genuinely capable sensors and multi-lens systems

**The opportunity:** A phone on a tracking mount, running an INDI server, is a free astronomy camera most people already own.

---

## Target Device (Primary)

**Google Pixel 6 Pro**

| Lens | Sensor | Resolution | Pixel Size | Est. Pixel Scale | FoV Character |
|------|--------|------------|------------|-----------------|---------------|
| Ultrawide | Sony IMX481 | 12MP | ~2.0μm | ~7–8"/px | Wide nebulae, Milky Way, large mosaics |
| Main | Samsung GN1 | 50MP | 1.2μm (2.4μm binned) | ~2.5"/px | Andromeda, large clusters |
| Telephoto (4x) | Samsung GM1 | 48MP periscope | ~1.0μm | ~0.8"/px | Galaxies, Moon, tight clusters |

Each lens is exposed as a **separate INDI CCD device** with accurate pixel scale metadata so Ekos plate solving and mosaic planning work automatically.

---

## Phase 1 — Android INDI Camera App

**Goal:** Single APK. Opens on phone. Ekos on PC sees it as a camera. Captures RAW frames. Ships FITS.

### Tech Stack

- **Language:** Kotlin
- **Camera API:** Camera2 (not CameraX — need full RAW/manual control)
- **INDI:** Implement INDI server from scratch in Kotlin (XML over TCP, port 7624)
- **RAW format:** DNG capture via `ImageReader` with `RAW_SENSOR` format
- **FITS conversion:** Convert DNG → FITS on device before sending to client
- **Networking:** WiFi (same network as PC). Phone hotspot supported for field use.
- **Min SDK:** Android 10+ (API 29) — Camera2 RAW well supported

### INDI Devices to Implement (Phase 1)

```
indi_pocketscope_wide       # Ultrawide lens
indi_pocketscope_main       # Main lens  
indi_pocketscope_tele       # 4x telephoto
```

Each device exposes standard INDI CCD properties:
- `CCD_INFO` — pixel size, resolution, bit depth
- `CCD_EXPOSURE` — trigger capture, exposure duration
- `CCD_FRAME` — ROI support
- `CCD_BINNING` — pixel binning (map to Camera2 binning modes)
- `CCD_TEMPERATURE` — report sensor temp if available
- `FITS_HEADER` — standard astronomy FITS headers

### Manual Camera Controls (INDI properties → Camera2)

| INDI Property | Camera2 Key | Notes |
|--------------|-------------|-------|
| ISO | `SENSOR_SENSITIVITY` | Range: 50–12800+ |
| Shutter speed | `SENSOR_EXPOSURE_TIME` | Nanoseconds in Camera2 |
| Focus | `LENS_FOCUS_DISTANCE` | 0.0 = infinity (stars) |
| White balance | `COLOR_CORRECTION_MODE` | Manual for astrophotography |
| Noise reduction | `NOISE_REDUCTION_MODE` | Off for stacking workflows |

### FITS Output

FITS is simple: fixed-width ASCII header blocks + raw pixel data. Implement minimal FITS writer:

```kotlin
// Key FITS headers needed for plate solving
SIMPLE  = T
BITPIX  = 16          // 16-bit unsigned
NAXIS   = 2
NAXIS1  = [width]
NAXIS2  = [height]
BAYERPAT= 'RGGB'      // Pixel 6 Pro Bayer pattern
XPIXSZ  = [microns]   // Pixel size X
YPIXSZ  = [microns]   // Pixel size Y
FOCALLEN= [mm]        // Focal length for plate solver hint
EXPTIME = [seconds]
GAIN    = [iso]
```

### DNG → FITS Pipeline

```
Camera2 RAW_SENSOR capture
    └── ImageReader → RAW buffer (Bayer data)
        └── Extract metadata from DNG (exposure, ISO, pixel size)
            └── Write FITS header
                └── Write raw Bayer pixel data (16-bit)
                    └── Send to INDI client via TCP blob
```

Do NOT debayer on device — send raw Bayer data. Let the client (Ekos/PixInsight) debayer with proper algorithms.

### INDI Server Implementation Notes

- INDI is XML over TCP, port 7624
- Server sends `defXXXVector` to announce properties
- Client sends `newXXXVector` to set values
- Server sends `setXXXVector` to update values
- Blobs (image data) sent as base64-encoded `setBLOBVector`
- Implement a minimal subset: Text, Number, Switch, BLOB vector types
- Reference: https://www.indilib.org/develop/developer-manual/104-indi-drivers.html

### Camera Switching

Camera2 requires closing and reopening session when switching physical lenses (~2 seconds). Handle gracefully:
- INDI client selects device (e.g. switches from Wide to Tele)
- App closes current Camera2 session
- Opens new session for selected lens
- Reports ready state back to INDI client
- Ekos handles the delay naturally via connection state

### UI (Phase 1 — Minimal)

The phone is mounted on a telescope. Nobody is looking at the screen. UI needs:
- **Dark red night vision mode** (preserves dark adaptation)
- Connection status (INDI server IP + port, client connected y/n)
- Active device indicator (which lens)
- Live preview (low res, just for framing)
- Manual override controls (for use without Ekos)
- Keep screen on setting

---

## Phase 2 — Standalone Mode (No PC Required)

**Goal:** Phone IS the observatory. No laptop, no Pi, no hand controller.

### Additional Components

**Local Plate Solving — ASTAP**
- ASTAP CLI runs on ARM Linux = runs on Android via bundled binary or via Termux-style execution
- Star database: H18 or H17 (~500MB) — store on device
- Workflow: capture frame → save temp FITS → call ASTAP → parse `.ini` result → sync mount

**INDI Telescope Driver**
- Speak SkyWatcher (Synta) protocol — most common goto mounts (EQ3, EQ5, AZ-GTi, Star Adventurer GTi)
- Connect via:
  - **Bluetooth Serial** — HC-05 or BT-to-RS232 adapter on mount hand controller port
  - **USB OTG** — Direct USB-serial via Android USB host mode
  - **WiFi** — For mounts with built-in WiFi (AZ-GTi, Celestron Evolution)
- Expose as INDI `TELESCOPE` device (internally, for local use)

**Standalone GoTo Workflow**
```
1. Level mount, point roughly at target area
2. App captures frame
3. ASTAP plate solves → gets actual RA/Dec
4. Sync mount coordinates
5. Calculate slew to target
6. Drive mount motors
7. Verify with second plate solve
8. Begin imaging sequence
```

**Minimal Sequencer**
- Target list (RA/Dec or object name)
- Per-target: lens selection, exposure time, ISO, frame count
- Dithering between frames (small random mount nudge to average out hot pixels)
- Meridian flip handling (for equatorial mounts)

---

## Phase 3 — Open Hardware Mount

**Goal:** Complete standalone system. Phone + mount = full smart telescope. ~$50 in parts.

### Hardware

```
Three-axis motorized alt-az mount
├── Azimuth axis — NEMA 14 stepper + TMC2209 driver
├── Altitude axis — NEMA 14 stepper + TMC2209 driver  
├── Field rotator — NEMA 11 stepper + ring gear (derotation)
├── ESP32 microcontroller (WiFi + Bluetooth built in)
├── Custom PCB (JLCPCB fabrication)
├── 3D printed enclosure + gears
├── Phone cold shoe clamp on rotator
└── USB-C powered (from power bank)
```

### ESP32 Firmware

- Speaks SkyWatcher protocol over Bluetooth Serial + WiFi
- INDI telescope driver on phone connects to it identically to a real mount
- Three-axis coordinate transform: RA/Dec → Alt/Az + parallactic angle (field derotation)
- Parallactic angle math drives rotator continuously during imaging

### Field Derotation Math

```
parallactic_angle = atan2(
    sin(hour_angle),
    tan(latitude) * cos(dec) - sin(dec) * cos(hour_angle)
)
```

Update rotator position continuously as target tracks across sky. Eliminates field rotation that normally plagues alt-az mounts for long exposures.

### Bill of Materials (Estimated)

| Part | Cost |
|------|------|
| ESP32 dev board | ~$5 |
| 2x NEMA 14 steppers | ~$12 |
| 1x NEMA 11 stepper | ~$6 |
| 3x TMC2209 drivers | ~$15 |
| PCB fabrication (JLCPCB) | ~$5 |
| 3D printed parts (filament) | ~$5 |
| Phone clamp + cold shoe | ~$5 |
| **Total** | **~$53** |

---

## Competitive Landscape

| | PocketScope | Dwarf II | Stellarmate (Pi) | ZWO ASI + mount |
|--|------------|----------|-----------------|----------------|
| Cost | ~$0 (+ phone) | $450 | ~$150 | $500–2000+ |
| RAW capture | ✅ | ❌ | ✅ | ✅ |
| Open source | ✅ | ❌ | Partial | N/A |
| INDI compatible | ✅ | ❌ | ✅ | ✅ |
| Multi-lens | ✅ | ❌ | ❌ | ❌ |
| Standalone goto | ✅ Ph2 | ✅ | ✅ | ❌ |
| Field derotation | ✅ Ph3 | ✅ | N/A | N/A |
| Hackable | ✅ | ❌ | ✅ | Partial |

---

## Repository Structure (Suggested)

```
pocketscope/
├── app/                        # Android app (Kotlin)
│   ├── indi/                   # INDI server implementation
│   │   ├── IndiServer.kt
│   │   ├── CcdDriver.kt
│   │   └── TelescopeDriver.kt
│   ├── camera/                 # Camera2 abstraction
│   │   ├── CameraManager.kt
│   │   ├── RawCaptureSession.kt
│   │   └── LensProfile.kt      # Per-lens metadata (pixel size etc)
│   ├── fits/                   # FITS writer
│   │   └── FitsWriter.kt
│   ├── solver/                 # ASTAP integration (Phase 2)
│   │   └── AstapSolver.kt
│   └── ui/
│       └── MainActivity.kt
├── firmware/                   # ESP32 mount firmware (Phase 3)
│   └── pocketscope_mount/
├── hardware/                   # KiCad PCB + OpenSCAD models (Phase 3)
│   ├── pcb/
│   └── mechanical/
└── docs/
    ├── indi-protocol-notes.md
    ├── camera2-raw-notes.md
    └── pixel6pro-sensor-data.md
```

---

## License

**GPLv2** — matches libindi and the broader open source astronomy ecosystem. Ensures derivative works stay open.

---

## Key References

- INDI protocol spec: https://www.indilib.org/develop/developer-manual.html
- Camera2 RAW sample: https://github.com/android/camera-samples/tree/main/Camera2Raw
- ASTAP: https://www.hnsky.org/astap.htm
- SkyWatcher motor protocol: https://inter-static.skywatcher.com/downloads/skywatcher_motor_controller_command_set.pdf
- FITS standard: https://fits.gsfc.nasa.gov/fits_standard.html
- Pixel 6 Pro sensor specs: Samsung GN1 datasheet

---

## Phase 1 — First Session Goals

Start here tomorrow:

1. `CameraManager.kt` — enumerate Camera2 logical cameras, identify wide/main/tele, read pixel size from `CameraCharacteristics`
2. `RawCaptureSession.kt` — open RAW_SENSOR ImageReader, capture single DNG frame, dump to file
3. `FitsWriter.kt` — take raw buffer + metadata, write valid FITS file, verify with FITS viewer
4. `IndiServer.kt` — TCP server on port 7624, handle client connection, send `defNumberVector` for one property
5. Wire together: Ekos connects → requests exposure → app captures RAW → sends FITS blob back

**That end-to-end path, even for one lens, is v0.1.**

---

*Generated from design session — March 2026*
