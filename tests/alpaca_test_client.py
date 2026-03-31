#!/usr/bin/env python3
"""
PocketScope Alpaca test client.

Connects to the PocketScope Alpaca server over HTTP, discovers devices,
lists properties, and optionally captures an image or moves the focuser.
Much faster debug cycle than going through full NINA/SGP.

Usage:
    # Discover server and list all devices + properties
    python tests/alpaca_test_client.py --host 192.168.1.X

    # Capture a 2s exposure and save FITS
    python tests/alpaca_test_client.py --host 192.168.1.X --capture 2.0

    # Move focuser to absolute position 500
    python tests/alpaca_test_client.py --host 192.168.1.X --focus-abs 500

    # Full exercise: connect, query all properties, capture, move focus
    python tests/alpaca_test_client.py --host 192.168.1.X --capture 1.0 --focus-abs 500

    # Use UDP discovery to find the server automatically
    python tests/alpaca_test_client.py --discover
"""

import argparse
import json
import logging
import socket
import struct
import sys
import time

import requests

logging.basicConfig(
    level=logging.DEBUG,
    format="%(asctime)s %(levelname)-8s %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger("alpaca_test")

# ASCOM error codes
ERR_OK = 0
ERR_NOT_IMPLEMENTED = 0x400
ERR_INVALID_VALUE = 0x401
ERR_NOT_CONNECTED = 0x407
ERR_INVALID_OPERATION = 0x40B


class AlpacaClient:
    """Simple Alpaca HTTP client for testing PocketScope."""

    def __init__(self, host, port=11111):
        self.base = f"http://{host}:{port}"
        self.tx_id = 0

    def _next_tx(self):
        self.tx_id += 1
        return self.tx_id

    def get(self, path, **extra_params):
        params = {"ClientTransactionID": self._next_tx(), **extra_params}
        url = f"{self.base}{path}"
        log.debug(f"GET {url}")
        r = requests.get(url, params=params, timeout=30)
        data = r.json()
        err = data.get("ErrorNumber", 0)
        if err != 0:
            log.warning(f"  Error {err}: {data.get('ErrorMessage', '?')}")
        return data

    def put(self, path, **form_data):
        form_data["ClientTransactionID"] = self._next_tx()
        url = f"{self.base}{path}"
        log.debug(f"PUT {url} {form_data}")
        r = requests.put(url, data=form_data, timeout=60)
        data = r.json()
        err = data.get("ErrorNumber", 0)
        if err != 0:
            log.warning(f"  Error {err}: {data.get('ErrorMessage', '?')}")
        return data

    def get_value(self, path, **extra_params):
        """GET and return just the Value field."""
        data = self.get(path, **extra_params)
        return data.get("Value")

    def device_get(self, device_type, device_number, method):
        return self.get(f"/api/v1/{device_type}/{device_number}/{method}")

    def device_put(self, device_type, device_number, method, **form_data):
        return self.put(f"/api/v1/{device_type}/{device_number}/{method}", **form_data)

    def device_get_value(self, device_type, device_number, method):
        return self.get_value(f"/api/v1/{device_type}/{device_number}/{method}")


# ---------------------------------------------------------------------------
# Discovery
# ---------------------------------------------------------------------------

def udp_discover(timeout=3):
    """Send Alpaca UDP discovery broadcast and return list of (host, port)."""
    DISCOVERY_PORT = 32227
    msg = b"alpacadiscovery1"

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    sock.settimeout(timeout)

    log.info(f"Broadcasting Alpaca discovery on port {DISCOVERY_PORT}...")
    sock.sendto(msg, ("<broadcast>", DISCOVERY_PORT))

    servers = []
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            data, addr = sock.recvfrom(1024)
            try:
                resp = json.loads(data.decode())
                port = resp.get("AlpacaPort", 11111)
                log.info(f"  Found server: {addr[0]}:{port}")
                servers.append((addr[0], port))
            except (json.JSONDecodeError, UnicodeDecodeError):
                log.debug(f"  Non-JSON response from {addr}: {data!r}")
        except socket.timeout:
            break

    sock.close()
    if not servers:
        log.warning("No Alpaca servers found via UDP discovery")
    return servers


def management_info(client):
    """Query management endpoints and print server info."""
    log.info(f"\n{'='*60}")
    log.info("Management Endpoints")
    log.info(f"{'='*60}")

    versions = client.get_value("/management/apiversions")
    log.info(f"  API versions: {versions}")

    desc = client.get("/management/v1/description")
    val = desc.get("Value", {})
    log.info(f"  Server: {val.get('ServerName', '?')}")
    log.info(f"  Manufacturer: {val.get('Manufacturer', '?')}")
    log.info(f"  Version: {val.get('ManufacturerVersion', '?')}")
    log.info(f"  Location: {val.get('Location', '?')}")

    return val


def list_devices(client):
    """List all configured devices from the management endpoint."""
    data = client.get("/management/v1/configureddevices")
    devices = data.get("Value", [])

    log.info(f"\n{'='*60}")
    log.info(f"Configured Devices ({len(devices)})")
    log.info(f"{'='*60}")

    for d in devices:
        log.info(f"  [{d.get('DeviceType', '?')}:{d.get('DeviceNumber', '?')}] "
                 f"{d.get('DeviceName', '?')} (UUID: {d.get('UniqueID', '?')[:12]}...)")

    return devices


# ---------------------------------------------------------------------------
# Camera
# ---------------------------------------------------------------------------

CAMERA_PROPS_GET = [
    # Sensor geometry
    "cameraxsize", "cameraysize", "pixelsizex", "pixelsizey",
    "numx", "numy", "startx", "starty",
    "binx", "biny", "maxbinx", "maxbiny",
    # Sensor type
    "sensortype", "sensorname", "bayeroffsetx", "bayeroffsety",
    # Gain / ISO
    "gain", "gainmin", "gainmax",
    # Exposure
    "exposuremin", "exposuremax", "exposureresolution",
    "lastexposureduration", "lastexposurestarttime",
    # State
    "camerastate", "imageready", "percentcompleted",
    # Capabilities
    "canabortexposure", "canstopexposure",
    "cangetcoolerpower", "cansetccdtemperature",
    "canfastreadout", "canasymmetricbin", "canpulseguide", "hasshutter",
    # Readout
    "readoutmode", "readoutmodes",
    # ADU
    "maxadu",
    # Cooler
    "cooleron",
]


def camera_info(client, device_number=0):
    """Query and display all camera properties."""
    dt = "camera"
    dn = device_number

    # Common properties
    log.info(f"\n{'='*60}")
    log.info(f"Camera {dn} Properties")
    log.info(f"{'='*60}")

    for prop in ["name", "description", "driverinfo", "driverversion",
                 "interfaceversion", "connected"]:
        val = client.device_get_value(dt, dn, prop)
        log.info(f"  {prop}: {val}")

    log.info(f"\n  --- Sensor & Capabilities ---")
    results = {}
    for prop in CAMERA_PROPS_GET:
        data = client.device_get(dt, dn, prop)
        err = data.get("ErrorNumber", 0)
        val = data.get("Value")
        if err == ERR_NOT_IMPLEMENTED:
            results[prop] = "(not implemented)"
        elif err != 0:
            results[prop] = f"(error {err})"
        else:
            results[prop] = val
        log.info(f"  {prop}: {results[prop]}")

    return results


def camera_capture(client, device_number, duration, output_path, use_imagebytes=True):
    """Start an exposure, poll for completion, and download the image."""
    dt = "camera"
    dn = device_number

    # Start exposure
    log.info(f"\nStarting {duration}s exposure on camera {dn}...")
    resp = client.device_put(dt, dn, "startexposure", Duration=str(duration), Light="true")
    if resp.get("ErrorNumber", 0) != 0:
        log.error(f"startexposure failed: {resp.get('ErrorMessage')}")
        return False

    # Poll for completion
    poll_interval = 0.5
    timeout = duration + 30
    deadline = time.time() + timeout

    while time.time() < deadline:
        state = client.device_get_value(dt, dn, "camerastate")
        pct = client.device_get_value(dt, dn, "percentcompleted")
        ready = client.device_get_value(dt, dn, "imageready")
        log.info(f"  camerastate={state} percentcompleted={pct}% imageready={ready}")

        if ready:
            break

        if state == 0 and not ready:
            # Idle but image not ready - might be an error
            log.warning("Camera returned to idle without image ready")
            break

        time.sleep(poll_interval)
    else:
        log.error(f"Capture timed out after {timeout}s")
        return False

    if not ready:
        log.error("Image not ready after exposure")
        return False

    # Download image
    log.info("Downloading image...")

    if use_imagebytes:
        image_data = _download_imagebytes(client, dt, dn)
    else:
        image_data = _download_imagearray_json(client, dt, dn)

    if image_data is None:
        return False

    if output_path:
        with open(output_path, "wb") as f:
            f.write(image_data)
        log.info(f"Saved {len(image_data)} bytes to {output_path}")

    return True


def _download_imagebytes(client, dt, dn):
    """Download image as ASCOM ImageBytes (binary, fast)."""
    url = f"{client.base}/api/v1/{dt}/{dn}/imagearray"
    params = {"ClientTransactionID": client._next_tx()}
    headers = {"Accept": "application/imagebytes"}

    log.info(f"  GET {url} (ImageBytes)")
    r = requests.get(url, params=params, headers=headers, timeout=120)

    if r.status_code != 200:
        log.error(f"  HTTP {r.status_code}: {r.text[:200]}")
        return None

    data = r.content
    if len(data) < 44:
        log.error(f"  Response too short for ImageBytes header: {len(data)} bytes")
        return None

    # Parse the 44-byte header
    header = struct.unpack_from("<11i", data, 0)
    meta_version, err_num, client_tx, server_tx, data_start = header[:5]
    elem_type, tx_type, rank, dim1, dim2, dim3 = header[5:]

    log.info(f"  ImageBytes header: version={meta_version} error={err_num} "
             f"rank={rank} dims={dim1}x{dim2}x{dim3} "
             f"elemType={elem_type} txType={tx_type} dataStart={data_start}")
    log.info(f"  Total bytes: {len(data)} (header={data_start}, pixels={len(data)-data_start})")

    if err_num != 0:
        log.error(f"  ImageBytes error: {err_num}")
        return None

    return data


def _download_imagearray_json(client, dt, dn):
    """Download image as JSON ImageArray (slow, for comparison testing)."""
    url = f"{client.base}/api/v1/{dt}/{dn}/imagearray"
    params = {"ClientTransactionID": client._next_tx()}
    headers = {"Accept": "application/json"}

    log.info(f"  GET {url} (JSON ImageArray)")
    r = requests.get(url, params=params, headers=headers, timeout=300)

    if r.status_code != 200:
        log.error(f"  HTTP {r.status_code}: {r.text[:200]}")
        return None

    data = r.json()
    err = data.get("ErrorNumber", 0)
    if err != 0:
        log.error(f"  ImageArray error {err}: {data.get('ErrorMessage')}")
        return None

    array = data.get("Value", [])
    rank = data.get("Rank", 0)
    arr_type = data.get("Type", 0)
    log.info(f"  ImageArray: type={arr_type} rank={rank} "
             f"columns={len(array)} rows={len(array[0]) if array else 0}")

    # Return raw JSON bytes for saving
    return json.dumps(data).encode()


# ---------------------------------------------------------------------------
# Focuser
# ---------------------------------------------------------------------------

FOCUSER_PROPS_GET = [
    "absolute", "ismoving", "maxincrement", "maxstep", "position",
    "stepsize", "tempcomp", "tempcompavailable", "temperature",
]


def focuser_info(client, device_number=0):
    """Query and display all focuser properties."""
    dt = "focuser"
    dn = device_number

    log.info(f"\n{'='*60}")
    log.info(f"Focuser {dn} Properties")
    log.info(f"{'='*60}")

    for prop in ["name", "description", "driverinfo", "driverversion",
                 "interfaceversion", "connected"]:
        val = client.device_get_value(dt, dn, prop)
        log.info(f"  {prop}: {val}")

    log.info(f"\n  --- Focuser Capabilities ---")
    results = {}
    for prop in FOCUSER_PROPS_GET:
        data = client.device_get(dt, dn, prop)
        err = data.get("ErrorNumber", 0)
        val = data.get("Value")
        if err == ERR_NOT_IMPLEMENTED:
            results[prop] = "(not implemented)"
        elif err != 0:
            results[prop] = f"(error {err})"
        else:
            results[prop] = val
        log.info(f"  {prop}: {results[prop]}")

    return results


def focuser_move(client, position, device_number=0):
    """Move focuser to an absolute position."""
    dt = "focuser"
    dn = device_number

    log.info(f"\nMoving focuser {dn} to position {position}...")

    resp = client.device_put(dt, dn, "move", Position=str(position))
    if resp.get("ErrorNumber", 0) != 0:
        log.error(f"Move failed: {resp.get('ErrorMessage')}")
        return False

    # Read back position
    new_pos = client.device_get_value(dt, dn, "position")
    log.info(f"  Position after move: {new_pos}")
    return True


def focuser_halt(client, device_number=0):
    """Send halt command to focuser."""
    log.info(f"\nHalting focuser {device_number}...")
    resp = client.device_put("focuser", device_number, "halt")
    if resp.get("ErrorNumber", 0) != 0:
        log.error(f"Halt failed: {resp.get('ErrorMessage')}")
        return False
    log.info("  Halt OK")
    return True


# ---------------------------------------------------------------------------
# Connected check exercise
# ---------------------------------------------------------------------------

def test_connected_guard(client, device_type, device_number):
    """Test that the NOT_CONNECTED guard works correctly."""
    dt = device_type
    dn = device_number

    log.info(f"\n{'='*60}")
    log.info(f"Testing NOT_CONNECTED guard: {dt}/{dn}")
    log.info(f"{'='*60}")

    # Disconnect
    log.info("  Disconnecting device...")
    client.device_put(dt, dn, "connected", Connected="false")

    # Verify disconnected
    connected = client.device_get_value(dt, dn, "connected")
    log.info(f"  connected={connected}")
    assert connected is False, f"Expected False, got {connected}"

    # Try a device-specific method (should fail with NOT_CONNECTED)
    test_method = "position" if dt == "focuser" else "camerastate"
    data = client.device_get(dt, dn, test_method)
    err = data.get("ErrorNumber", 0)
    log.info(f"  GET {test_method} while disconnected: error={err} ({data.get('ErrorMessage', '')})")
    if err == ERR_NOT_CONNECTED:
        log.info("  PASS: Got NOT_CONNECTED error as expected")
    else:
        log.error(f"  FAIL: Expected error {ERR_NOT_CONNECTED}, got {err}")

    # Reconnect
    log.info("  Reconnecting device...")
    client.device_put(dt, dn, "connected", Connected="true")

    # Verify reconnected
    connected = client.device_get_value(dt, dn, "connected")
    log.info(f"  connected={connected}")

    # Retry the method (should work now)
    data = client.device_get(dt, dn, test_method)
    err = data.get("ErrorNumber", 0)
    log.info(f"  GET {test_method} while connected: error={err} value={data.get('Value')}")
    if err == 0:
        log.info("  PASS: Method works when connected")
    else:
        log.error(f"  FAIL: Expected no error, got {err}")


# ---------------------------------------------------------------------------
# TempComp error code test
# ---------------------------------------------------------------------------

def test_tempcomp_error(client, device_number=0):
    """Test that enabling tempcomp when unavailable returns NOT_IMPLEMENTED."""
    dt = "focuser"
    dn = device_number

    log.info(f"\n{'='*60}")
    log.info("Testing tempcomp error code")
    log.info(f"{'='*60}")

    # Verify tempcomp is not available
    avail = client.device_get_value(dt, dn, "tempcompavailable")
    log.info(f"  tempcompavailable: {avail}")

    if avail:
        log.info("  Skipping (tempcomp is available on this device)")
        return

    # Try to enable tempcomp
    data = client.device_put(dt, dn, "tempcomp", TempComp="true")
    err = data.get("ErrorNumber", 0)
    log.info(f"  PUT tempcomp=true: error={err} ({data.get('ErrorMessage', '')})")

    if err == ERR_NOT_IMPLEMENTED:
        log.info("  PASS: Got NOT_IMPLEMENTED as expected")
    else:
        log.error(f"  FAIL: Expected {ERR_NOT_IMPLEMENTED}, got {err}")

    # Setting to false should succeed
    data = client.device_put(dt, dn, "tempcomp", TempComp="false")
    err = data.get("ErrorNumber", 0)
    log.info(f"  PUT tempcomp=false: error={err}")
    if err == 0:
        log.info("  PASS: Setting false accepted")
    else:
        log.error(f"  FAIL: Expected 0, got {err}")


# ---------------------------------------------------------------------------
# Case-insensitive parameter test
# ---------------------------------------------------------------------------

def test_case_insensitive_params(client, device_number=0):
    """Test that parameter names are case-insensitive per Alpaca spec."""
    dt = "focuser"
    dn = device_number

    log.info(f"\n{'='*60}")
    log.info("Testing case-insensitive parameter names")
    log.info(f"{'='*60}")

    # Move using different cases for the Position parameter
    cases = [
        ("Position", "100"),
        ("position", "200"),
        ("POSITION", "300"),
        ("pOsItIoN", "400"),
    ]

    for param_name, target in cases:
        form = {"ClientTransactionID": client._next_tx(), param_name: target}
        url = f"{client.base}/api/v1/{dt}/{dn}/move"
        r = requests.put(url, data=form, timeout=10)
        data = r.json()
        err = data.get("ErrorNumber", 0)
        pos = client.device_get_value(dt, dn, "position")
        expected = int(target)

        if err == 0 and pos == expected:
            log.info(f"  PASS: {param_name}={target} -> position={pos}")
        else:
            log.error(f"  FAIL: {param_name}={target} -> error={err} position={pos} (expected {expected})")


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description="PocketScope Alpaca test client")
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
    parser.add_argument("--output", "-o", default="alpaca_capture.bin",
                        help="Output path for captured image (default: alpaca_capture.bin)")
    parser.add_argument("--json-image", action="store_true",
                        help="Download image as JSON instead of ImageBytes")
    parser.add_argument("--focus-abs", type=int, default=None, metavar="POS",
                        help="Move focuser to absolute position")
    parser.add_argument("--focus-halt", action="store_true",
                        help="Send halt command to focuser")

    # Tests
    parser.add_argument("--test-connected", action="store_true",
                        help="Test NOT_CONNECTED guard on focuser and camera")
    parser.add_argument("--test-tempcomp", action="store_true",
                        help="Test tempcomp error code (should be NOT_IMPLEMENTED)")
    parser.add_argument("--test-case", action="store_true",
                        help="Test case-insensitive parameter handling")
    parser.add_argument("--test-all", action="store_true",
                        help="Run all tests")

    args = parser.parse_args()

    # Discover or use provided host
    if args.discover or args.host is None:
        servers = udp_discover()
        if servers:
            host, port = servers[0]
            if args.host is None:
                args.host = host
                args.port = port
        elif args.host is None:
            log.error("No --host provided and UDP discovery found nothing")
            sys.exit(1)

    client = AlpacaClient(args.host, args.port)
    log.info(f"Target: {client.base}")

    # Always show management info and device list
    management_info(client)
    devices = list_devices(client)

    # Show camera and focuser properties
    camera_info(client, args.camera)
    focuser_info(client, args.focuser)

    # Capture
    if args.capture is not None:
        use_imagebytes = not args.json_image
        if not camera_capture(client, args.camera, args.capture, args.output,
                              use_imagebytes=use_imagebytes):
            sys.exit(1)

    # Focuser actions
    if args.focus_abs is not None:
        if not focuser_move(client, args.focus_abs, args.focuser):
            sys.exit(1)

    if args.focus_halt:
        if not focuser_halt(client, args.focuser):
            sys.exit(1)

    # Tests
    if args.test_all:
        args.test_connected = True
        args.test_tempcomp = True
        args.test_case = True

    if args.test_case:
        test_case_insensitive_params(client, args.focuser)

    if args.test_tempcomp:
        test_tempcomp_error(client, args.focuser)

    if args.test_connected:
        test_connected_guard(client, "focuser", args.focuser)
        test_connected_guard(client, "camera", args.camera)

    log.info(f"\n{'='*60}")
    log.info("Done.")
    log.info(f"{'='*60}")


if __name__ == "__main__":
    main()
