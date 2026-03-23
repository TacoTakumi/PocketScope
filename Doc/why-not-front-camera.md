# Why PocketScope Doesn't Use the Front (Selfie) Camera

A reasonable question — the phone has multiple cameras, so why not expose the
front-facing one too? Short answer: it lacks the two capabilities astrophotography
fundamentally requires.

## RAW Sensor Output

PocketScope captures raw Bayer data (`RAW_SENSOR` format) and wraps it in FITS files.
This is essential for the calibration workflow that astrophotography depends on —
dark frame subtraction, flat field correction, and proper debayering all require
unprocessed sensor data.

Front cameras on virtually all Android phones **do not support `RAW_SENSOR` output**.
They are designed for JPEG/YUV selfie processing, not scientific imaging. Without raw
data, the captured images cannot be calibrated and are unsuitable for stacking or
plate solving.

## Focus Control

Astrophotography requires focusing at infinity. Front cameras are almost universally
**fixed-focus**, locked at roughly arm's length (~0.5m). Stars would appear as large
blurred discs rather than point sources. There is no focus motor to control, so the
INDI focuser device would be meaningless.

## Other Limitations

- **Small sensor and narrow aperture** — Typically 2–4 MP effective resolution with
  f/2.0–2.4 aperture. Far less light-gathering capability than rear sensors, which
  matters enormously for faint deep-sky objects.
- **Single lens** — No ultrawide/tele options. You get one fixed focal length.
- **Mounting orientation** — With the front camera pointing at the sky, the screen
  faces down and is invisible. You'd need a mirror or remote display to operate the
  app, adding complexity for no benefit.

## What About All-Sky / Meteor Monitoring?

This is the one scenario where pointing a camera straight up from a flat surface
makes sense. But even here, the lack of RAW output means the data can't be
properly calibrated, and the fixed focus means stars won't be sharp. A phone
mounted screen-up with a rear camera pointed at the sky via a mirror or prism
would produce vastly better results.

## Implementation

In the codebase, `LensEnumerator` filters to rear-facing cameras only:

```kotlin
val facing = chars.get(CameraCharacteristics.LENS_FACING)
if (facing != CameraCharacteristics.LENS_FACING_BACK) continue
```

This is intentional and unlikely to change unless a phone manufacturer produces a
front camera with RAW capture support and manual focus — which would be a first.
