#!/usr/bin/env python3
"""
PocketScope Alpaca test client using the alpyca library.

Uses the official ASCOM alpyca Python client to exercise the camera and
focuser — validates PocketScope works with a real ASCOM client library,
not just raw HTTP.

Usage:
    # Activate venv first:  source venv/bin/activate

    # Discover and list all devices + properties
    python tests/alpaca_alpyca_client.py --host 172.16.1.52

    # Capture a 2s exposure
    python tests/alpaca_alpyca_client.py --host 172.16.1.52 --capture 2.0

    # Move focuser to position 500
    python tests/alpaca_alpyca_client.py --host 172.16.1.52 --focus-abs 500

    # Auto-discover server on LAN
    python tests/alpaca_alpyca_client.py --discover

    # Exercise everything
    python tests/alpaca_alpyca_client.py --host 172.16.1.52 --capture 1.0 --focus-abs 500
"""

import argparse
import logging
import sys
import time

from alpaca.camera import Camera, CameraStates
from alpaca.focuser import Focuser
from alpaca.discovery import search_ipv4
from alpaca.management import description as mgmt_description
from alpaca.management import configureddevices as mgmt_configureddevices

logging.basicConfig(
    level=logging.DEBUG,
    format="%(asctime)s %(levelname)-8s %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger("alpyca_test")


# ---------------------------------------------------------------------------
# Discovery & Management
# ---------------------------------------------------------------------------

def discover_servers():
    """Use alpyca UDP discovery to find Alpaca servers on the LAN."""
    log.info("Running alpyca IPV4 discovery...")
    servers = search_ipv4(numquery=2, timeout=3, trace=True)
    if servers:
        for s in servers:
            log.info(f"  Found: {s}")
    else:
        log.warning("  No servers found")
    return servers


def show_management(host, port):
    """Show server description and configured devices via management API."""
    log.info(f"\n{'='*60}")
    log.info("Management Info")
    log.info(f"{'='*60}")

    addr = f"{host}:{port}"
    try:
        desc = mgmt_description(addr)
        log.info(f"  Server: {desc.get('ServerName', '?')}")
        log.info(f"  Manufacturer: {desc.get('Manufacturer', '?')}")
        log.info(f"  Version: {desc.get('ManufacturerVersion', '?')}")
        log.info(f"  Location: {desc.get('Location', '?')}")
    except Exception as e:
        log.error(f"  description failed: {e}")

    log.info("")
    devices = []
    try:
        devices = mgmt_configureddevices(addr)
        log.info(f"  Configured Devices ({len(devices)}):")
        for d in devices:
            log.info(f"    [{d.get('DeviceType', '?')}:{d.get('DeviceNumber', '?')}] "
                     f"{d.get('DeviceName', '?')}")
    except Exception as e:
        log.error(f"  configureddevices failed: {e}")

    return devices


# ---------------------------------------------------------------------------
# Camera
# ---------------------------------------------------------------------------

def camera_info(cam):
    """Query and display all readable camera properties."""
    log.info(f"\n{'='*60}")
    log.info(f"Camera Properties")
    log.info(f"{'='*60}")

    # Common
    safe_get(cam, "Name")
    safe_get(cam, "Description")
    safe_get(cam, "DriverInfo")
    safe_get(cam, "DriverVersion")
    safe_get(cam, "InterfaceVersion")
    safe_get(cam, "Connected")

    # Sensor geometry
    log.info("\n  --- Sensor Geometry ---")
    safe_get(cam, "CameraXSize")
    safe_get(cam, "CameraYSize")
    safe_get(cam, "PixelSizeX")
    safe_get(cam, "PixelSizeY")
    safe_get(cam, "NumX")
    safe_get(cam, "NumY")
    safe_get(cam, "StartX")
    safe_get(cam, "StartY")
    safe_get(cam, "BinX")
    safe_get(cam, "BinY")
    safe_get(cam, "MaxBinX")
    safe_get(cam, "MaxBinY")

    # Sensor type
    log.info("\n  --- Sensor Type ---")
    safe_get(cam, "SensorType")
    safe_get(cam, "SensorName")
    safe_get(cam, "BayerOffsetX")
    safe_get(cam, "BayerOffsetY")

    # Gain
    log.info("\n  --- Gain / ISO ---")
    safe_get(cam, "Gain")
    safe_get(cam, "GainMin")
    safe_get(cam, "GainMax")

    # Exposure
    log.info("\n  --- Exposure ---")
    safe_get(cam, "ExposureMin")
    safe_get(cam, "ExposureMax")
    safe_get(cam, "ExposureResolution")
    safe_get(cam, "MaxADU")

    # State
    log.info("\n  --- State ---")
    safe_get(cam, "CameraState")
    safe_get(cam, "ImageReady")
    safe_get(cam, "PercentCompleted")

    # Capabilities
    log.info("\n  --- Capabilities ---")
    safe_get(cam, "CanAbortExposure")
    safe_get(cam, "CanStopExposure")
    safe_get(cam, "CanGetCoolerPower")
    safe_get(cam, "CanSetCCDTemperature")
    safe_get(cam, "CanFastReadout")
    safe_get(cam, "CanAsymmetricBin")
    safe_get(cam, "CanPulseGuide")
    safe_get(cam, "HasShutter")

    # Readout
    log.info("\n  --- Readout ---")
    safe_get(cam, "ReadoutMode")
    safe_get(cam, "ReadoutModes")


def camera_capture(cam, duration, output_path):
    """Start exposure, poll for completion, download image array."""
    log.info(f"\nStarting {duration}s exposure...")

    try:
        cam.StartExposure(duration, True)
    except Exception as e:
        log.error(f"StartExposure failed: {e}")
        return False

    # Poll for completion
    timeout = duration + 30
    deadline = time.time() + timeout

    while time.time() < deadline:
        try:
            state = cam.CameraState
            ready = cam.ImageReady
            pct = safe_get_quiet(cam, "PercentCompleted", 0)
            log.info(f"  state={state} ready={ready} pct={pct}%")

            if ready:
                break

            if state == CameraStates.cameraIdle and not ready:
                log.warning("Camera idle but image not ready")
                break
        except Exception as e:
            log.warning(f"  Poll error: {e}")

        time.sleep(0.5)
    else:
        log.error(f"Capture timed out after {timeout}s")
        return False

    if not ready:
        log.error("Image not ready after exposure")
        return False

    # Download image
    log.info("Downloading image via ImageArray...")
    t0 = time.time()
    try:
        img = cam.ImageArray
        elapsed = time.time() - t0

        if img:
            cols = len(img)
            rows = len(img[0]) if cols > 0 else 0
            log.info(f"  ImageArray: {cols} columns x {rows} rows "
                     f"(downloaded in {elapsed:.1f}s)")

            if output_path:
                # Save as raw pixel dump for inspection
                import struct
                with open(output_path, "wb") as f:
                    for col in img:
                        for pixel in col:
                            f.write(struct.pack("<i", pixel))
                log.info(f"  Saved raw pixels to {output_path}")
        else:
            log.error("  ImageArray returned empty")
            return False

    except Exception as e:
        log.error(f"ImageArray download failed: {e}")
        return False

    # Log post-exposure info
    safe_get(cam, "LastExposureDuration")
    safe_get(cam, "LastExposureStartTime")

    return True


# ---------------------------------------------------------------------------
# Focuser
# ---------------------------------------------------------------------------

def focuser_info(foc):
    """Query and display all readable focuser properties."""
    log.info(f"\n{'='*60}")
    log.info(f"Focuser Properties")
    log.info(f"{'='*60}")

    safe_get(foc, "Name")
    safe_get(foc, "Description")
    safe_get(foc, "DriverInfo")
    safe_get(foc, "DriverVersion")
    safe_get(foc, "InterfaceVersion")
    safe_get(foc, "Connected")

    log.info("\n  --- Focuser Capabilities ---")
    safe_get(foc, "Absolute")
    safe_get(foc, "IsMoving")
    safe_get(foc, "MaxIncrement")
    safe_get(foc, "MaxStep")
    safe_get(foc, "Position")
    safe_get(foc, "StepSize")
    safe_get(foc, "TempComp")
    safe_get(foc, "TempCompAvailable")
    safe_get(foc, "Temperature")


def focuser_move(foc, position):
    """Move focuser to absolute position."""
    log.info(f"\nMoving focuser to position {position}...")

    try:
        foc.Move(position)
    except Exception as e:
        log.error(f"Move failed: {e}")
        return False

    # Poll IsMoving (should be immediate for PocketScope, but be correct)
    for _ in range(50):
        try:
            if not foc.IsMoving:
                break
        except Exception:
            pass
        time.sleep(0.1)

    pos = safe_get_quiet(foc, "Position", "?")
    log.info(f"  Position after move: {pos}")
    return True


def focuser_halt(foc):
    """Send halt command."""
    log.info("\nHalting focuser...")
    try:
        foc.Halt()
        log.info("  Halt OK")
        return True
    except Exception as e:
        log.error(f"Halt failed: {e}")
        return False


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def safe_get(device, prop_name):
    """Read a property, logging errors gracefully."""
    try:
        val = getattr(device, prop_name)
        log.info(f"  {prop_name}: {val}")
        return val
    except Exception as e:
        log.info(f"  {prop_name}: (error: {e})")
        return None


def safe_get_quiet(device, prop_name, default=None):
    """Read a property, returning default on error."""
    try:
        return getattr(device, prop_name)
    except Exception:
        return default


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description="PocketScope Alpaca test client (alpyca)")
    parser.add_argument("--host", default=None,
                        help="Alpaca server hostname/IP")
    parser.add_argument("--port", type=int, default=11111,
                        help="Alpaca server port (default: 11111)")
    parser.add_argument("--discover", action="store_true",
                        help="Use UDP broadcast to discover Alpaca servers")
    parser.add_argument("--camera", type=int, default=0, metavar="N",
                        help="Camera device number (default: 0)")
    parser.add_argument("--focuser", type=int, default=0, metavar="N",
                        help="Focuser device number (default: 0)")

    # Actions
    parser.add_argument("--capture", type=float, default=None, metavar="SEC",
                        help="Capture an image with this exposure time (seconds)")
    parser.add_argument("--output", "-o", default="alpyca_capture.bin",
                        help="Output path for captured image (default: alpyca_capture.bin)")
    parser.add_argument("--focus-abs", type=int, default=None, metavar="POS",
                        help="Move focuser to absolute position")
    parser.add_argument("--focus-halt", action="store_true",
                        help="Send halt command to focuser")

    args = parser.parse_args()

    # Discover or use provided host
    if args.discover or args.host is None:
        servers = discover_servers()
        if servers:
            # servers are "host:port" strings
            parts = servers[0].split(":")
            if args.host is None:
                args.host = parts[0]
                if len(parts) > 1:
                    args.port = int(parts[1])
        elif args.host is None:
            log.error("No --host provided and discovery found nothing")
            sys.exit(1)

    log.info(f"Target: {args.host}:{args.port}")

    # Management info
    show_management(args.host, args.port)

    # Create device objects
    cam = Camera(f"{args.host}:{args.port}", args.camera)
    foc = Focuser(f"{args.host}:{args.port}", args.focuser)

    # Connect
    log.info("\nConnecting devices...")
    try:
        cam.Connected = True
        log.info(f"  Camera connected: {cam.Connected}")
    except Exception as e:
        log.error(f"  Camera connect failed: {e}")

    try:
        foc.Connected = True
        log.info(f"  Focuser connected: {foc.Connected}")
    except Exception as e:
        log.error(f"  Focuser connect failed: {e}")

    # Show properties
    camera_info(cam)
    focuser_info(foc)

    # Capture
    if args.capture is not None:
        if not camera_capture(cam, args.capture, args.output):
            sys.exit(1)

    # Focuser actions
    if args.focus_abs is not None:
        if not focuser_move(foc, args.focus_abs):
            sys.exit(1)

    if args.focus_halt:
        focuser_halt(foc)

    log.info(f"\n{'='*60}")
    log.info("Done.")
    log.info(f"{'='*60}")


if __name__ == "__main__":
    main()
