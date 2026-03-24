#!/usr/bin/env python3
"""
PocketScope INDI test client using pyindi-client.

Connects to the PocketScope INDI server, discovers devices, lists all
properties, and optionally captures an image. Much faster debug cycle
than going through full Ekos/KStars.

Usage:
    # List devices and properties
    python tests/indi_test_client.py --host 192.168.1.X

    # Connect to a device and capture a 1s exposure
    python tests/indi_test_client.py --host 192.168.1.X --capture 1.0

    # Just connect (no capture)
    python tests/indi_test_client.py --host 192.168.1.X --connect

    # Save captured FITS to a specific path
    python tests/indi_test_client.py --host 192.168.1.X --capture 1.0 --output test.fits
"""

import argparse
import logging
import os
import sys
import time
import threading

import PyIndi

logging.basicConfig(
    level=logging.DEBUG,
    format="%(asctime)s %(levelname)-8s %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger("indi_test")


class PocketScopeClient(PyIndi.BaseClient):
    """INDI client with full callback logging for debugging."""

    def __init__(self):
        super().__init__()
        self.devices_seen = {}       # name -> BaseDevice
        self.blob_received = threading.Event()
        self.blob_data = None
        self.blob_format = None
        self.connected_event = threading.Event()
        self.exposure_done = threading.Event()
        self._props_received = {}    # (device, propname) -> Property

    # --- Callbacks ---

    def newDevice(self, d):
        name = d.getDeviceName()
        log.info(f"NEW DEVICE: {name}")
        self.devices_seen[name] = d

    def removeDevice(self, d):
        log.info(f"REMOVE DEVICE: {d.getDeviceName()}")

    def newProperty(self, p):
        device = p.getDeviceName()
        name = p.getName()
        ptype = p.getType()
        type_names = {
            PyIndi.INDI_NUMBER: "Number",
            PyIndi.INDI_SWITCH: "Switch",
            PyIndi.INDI_TEXT: "Text",
            PyIndi.INDI_BLOB: "BLOB",
            PyIndi.INDI_LIGHT: "Light",
        }
        tname = type_names.get(ptype, f"Unknown({ptype})")
        log.info(f"  NEW PROP: [{device}] {name} ({tname})")

        key = (device, name)
        self._props_received[key] = p

        # Log elements for number and text properties
        if ptype == PyIndi.INDI_NUMBER:
            for widget in PyIndi.PropertyNumber(p):
                log.info(f"    {widget.getName()} = {widget.getValue()} "
                         f"[{widget.getMin()}, {widget.getMax()}] step={widget.getStep()}")
        elif ptype == PyIndi.INDI_SWITCH:
            for widget in PyIndi.PropertySwitch(p):
                log.info(f"    {widget.getName()} = {widget.getStateAsString()}")
        elif ptype == PyIndi.INDI_TEXT:
            for widget in PyIndi.PropertyText(p):
                log.info(f"    {widget.getName()} = {widget.getText()}")

    def removeProperty(self, p):
        log.debug(f"  REMOVE PROP: [{p.getDeviceName()}] {p.getName()}")

    def updateProperty(self, p):
        device = p.getDeviceName()
        name = p.getName()
        ptype = p.getType()

        if ptype == PyIndi.INDI_NUMBER:
            prop = PyIndi.PropertyNumber(p)
            state = prop.getStateAsString()
            vals = {w.getName(): w.getValue() for w in prop}
            log.info(f"  UPDATE: [{device}] {name} state={state} {vals}")

            # Track exposure completion
            if name == "CCD_EXPOSURE":
                if state == "Ok":
                    log.info("  >> Exposure complete (state=Ok)")
                    self.exposure_done.set()
                elif state == "Alert":
                    log.error("  >> Exposure FAILED (state=Alert)")
                    self.exposure_done.set()
                elif state == "Busy":
                    log.info("  >> Exposure in progress (state=Busy)")

        elif ptype == PyIndi.INDI_SWITCH:
            prop = PyIndi.PropertySwitch(p)
            state = prop.getStateAsString()
            vals = {w.getName(): w.getStateAsString() for w in prop}
            log.info(f"  UPDATE: [{device}] {name} state={state} {vals}")

            # Track connection
            if name == "CONNECTION" and state == "Ok":
                self.connected_event.set()

        elif ptype == PyIndi.INDI_BLOB:
            prop = PyIndi.PropertyBlob(p)
            for blob in prop:
                self.blob_data = blob.getblobdata()
                self.blob_format = blob.getFormat()
                size = blob.getBlobLen()
                log.info(f"  BLOB: [{device}] {name} format={self.blob_format} size={size}")
                self.blob_received.set()
        else:
            log.debug(f"  UPDATE: [{device}] {name} type={ptype}")

    def newMessage(self, d, m):
        log.info(f"  MESSAGE: [{d.getDeviceName()}] {d.messageQueue(m)}")

    def serverConnected(self):
        log.info(f"Server connected: {self.getHost()}:{self.getPort()}")

    def serverDisconnected(self, code):
        log.warning(f"Server disconnected (code={code})")


def wait_for_property(client, device_name, prop_name, timeout=10):
    """Wait for a property to appear on a device."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        device = client.getDevice(device_name)
        if device:
            key = (device_name, prop_name)
            if key in client._props_received:
                return True
        time.sleep(0.2)
    return False


def list_devices(client, wait=3):
    """Wait for device discovery and print all devices + properties."""
    log.info(f"Waiting {wait}s for device discovery...")
    time.sleep(wait)

    devices = client.getDevices()
    if not devices:
        log.warning("No devices found!")
        return []

    log.info(f"\n{'='*60}")
    log.info(f"Found {len(devices)} device(s):")
    log.info(f"{'='*60}")

    names = []
    for device in devices:
        name = device.getDeviceName()
        names.append(name)
        log.info(f"\n--- {name} ---")
        for prop in device.getProperties():
            ptype = prop.getType()
            pname = prop.getName()
            if ptype == PyIndi.INDI_NUMBER:
                vals = {w.getName(): w.getValue() for w in PyIndi.PropertyNumber(prop)}
                log.info(f"  [Num]  {pname}: {vals}")
            elif ptype == PyIndi.INDI_SWITCH:
                vals = {w.getName(): w.getStateAsString() for w in PyIndi.PropertySwitch(prop)}
                log.info(f"  [Sw]   {pname}: {vals}")
            elif ptype == PyIndi.INDI_TEXT:
                vals = {w.getName(): w.getText() for w in PyIndi.PropertyText(prop)}
                log.info(f"  [Txt]  {pname}: {vals}")
            elif ptype == PyIndi.INDI_BLOB:
                log.info(f"  [BLOB] {pname}")
            elif ptype == PyIndi.INDI_LIGHT:
                log.info(f"  [Lt]   {pname}")

    log.info(f"\n{'='*60}")
    return names


def connect_device(client, device_name, timeout=10):
    """Send CONNECTION switch to connect a device."""
    log.info(f"Connecting to device: {device_name}")

    device = client.getDevice(device_name)
    if not device:
        log.error(f"Device '{device_name}' not found")
        return False

    conn = device.getSwitch("CONNECTION")
    if not conn:
        log.error("CONNECTION property not found")
        return False

    # Set CONNECT=On, DISCONNECT=Off
    conn[0].setState(PyIndi.ISS_ON)   # CONNECT
    conn[1].setState(PyIndi.ISS_OFF)  # DISCONNECT
    client.sendNewSwitch(conn)
    log.info("Sent CONNECTION request, waiting for Ok...")

    if client.connected_event.wait(timeout):
        log.info("Device connected!")
        return True
    else:
        log.error(f"Connection timed out after {timeout}s")
        return False


def capture_image(client, device_name, exposure_sec, output_path, timeout=None):
    """Trigger an exposure and wait for the BLOB."""
    if timeout is None:
        timeout = exposure_sec + 30  # generous timeout

    device = client.getDevice(device_name)
    if not device:
        log.error(f"Device '{device_name}' not found")
        return False

    # Enable BLOBs
    log.info(f"Enabling BLOBs for {device_name}")
    client.setBLOBMode(PyIndi.B_ALSO, device_name, None)

    # Wait for CCD_EXPOSURE property
    if not wait_for_property(client, device_name, "CCD_EXPOSURE"):
        log.error("CCD_EXPOSURE property not available")
        return False

    exp_prop = device.getNumber("CCD_EXPOSURE")
    if not exp_prop:
        log.error("Could not get CCD_EXPOSURE number property")
        return False

    # Log current state
    for w in exp_prop:
        log.info(f"  CCD_EXPOSURE element: {w.getName()} = {w.getValue()} "
                 f"[{w.getMin()}, {w.getMax()}]")

    # Reset events
    client.blob_received.clear()
    client.exposure_done.clear()

    # Set exposure value and send
    log.info(f"Starting {exposure_sec}s exposure...")
    exp_prop[0].setValue(exposure_sec)
    client.sendNewNumber(exp_prop)

    # Wait for BLOB
    log.info(f"Waiting for BLOB (timeout={timeout}s)...")
    if client.blob_received.wait(timeout):
        size = len(client.blob_data) if client.blob_data else 0
        log.info(f"BLOB received! size={size} format={client.blob_format}")

        if client.blob_data and output_path:
            with open(output_path, "wb") as f:
                f.write(client.blob_data)
            log.info(f"Saved to {output_path}")
        return True
    else:
        log.error(f"No BLOB received within {timeout}s")
        # Check if exposure_done fired
        if client.exposure_done.is_set():
            log.error("Exposure state changed but no BLOB arrived - "
                      "check enableBLOB handling and streamBlob()")
        else:
            log.error("Exposure state never reached Ok/Alert - "
                      "capture pipeline may have stalled")
        return False


def focus_move(client, device_name, prop_name, element_name, value, timeout=10):
    """Send a focus move command and wait for state=Ok on ABS_FOCUS_POSITION.

    Args:
        client: PocketScopeClient instance
        device_name: Focuser device name
        prop_name: Property name (ABS_FOCUS_POSITION or REL_FOCUS_POSITION)
        element_name: Element name within the property
        value: Numeric value to set
        timeout: Seconds to wait for Ok state

    Returns:
        True if move completed (state=Ok), False on timeout
    """
    if not wait_for_property(client, device_name, prop_name):
        log.error(f"{prop_name} property not available on {device_name}")
        return False

    device = client.getDevice(device_name)
    if not device:
        log.error(f"Device '{device_name}' not found")
        return False

    num_prop = device.getNumber(prop_name)
    if not num_prop:
        log.error(f"Could not get {prop_name} number property")
        return False

    # Set the element value
    for w in num_prop:
        if w.getName() == element_name:
            w.setValue(float(value))
            break
    else:
        log.error(f"Element {element_name} not found in {prop_name}")
        return False

    # Track state changes on ABS_FOCUS_POSITION via updateProperty callback
    move_done = threading.Event()
    original_update = client.updateProperty.__func__ if hasattr(client.updateProperty, '__func__') else None

    def _watch_focus_state(p):
        """Intercept updateProperty to detect ABS_FOCUS_POSITION state=Ok."""
        pname = p.getName()
        pdevice = p.getDeviceName()
        if pdevice == device_name and pname == "ABS_FOCUS_POSITION":
            prop = PyIndi.PropertyNumber(p)
            state = prop.getStateAsString()
            if state == "Ok":
                move_done.set()

    # Monkey-patch updateProperty to also watch for focus state
    _orig_update = type(client).updateProperty
    def _patched_update(self, p):
        _orig_update(self, p)
        _watch_focus_state(p)
    type(client).updateProperty = _patched_update

    log.info(f"Sending {prop_name} {element_name}={value}")
    client.sendNewNumber(num_prop)

    success = move_done.wait(timeout)

    # Restore original updateProperty
    type(client).updateProperty = _orig_update

    if success:
        # Read back absolute position
        if wait_for_property(client, device_name, "ABS_FOCUS_POSITION"):
            abs_prop = device.getNumber("ABS_FOCUS_POSITION")
            if abs_prop:
                pos = abs_prop[0].getValue()
                log.info(f"Focus move complete. Position: {pos}")
        return True
    else:
        log.error(f"Focus move timed out after {timeout}s")
        return False


def focus_info(client, device_name, timeout=10):
    """Print focuser properties: position, range, direction."""
    device = client.getDevice(device_name)
    if not device:
        log.error(f"Device '{device_name}' not found")
        return

    log.info(f"\n{'='*60}")
    log.info(f"Focuser info: {device_name}")
    log.info(f"{'='*60}")

    # ABS_FOCUS_POSITION
    if wait_for_property(client, device_name, "ABS_FOCUS_POSITION", timeout):
        prop = device.getNumber("ABS_FOCUS_POSITION")
        if prop:
            for w in prop:
                log.info(f"  ABS_FOCUS_POSITION: {w.getValue()} "
                         f"[min={w.getMin()}, max={w.getMax()}]")

    # REL_FOCUS_POSITION
    if wait_for_property(client, device_name, "REL_FOCUS_POSITION", timeout):
        prop = device.getNumber("REL_FOCUS_POSITION")
        if prop:
            for w in prop:
                log.info(f"  REL_FOCUS_POSITION: {w.getValue()} "
                         f"[min={w.getMin()}, max={w.getMax()}]")

    # FOCUS_MAX
    if wait_for_property(client, device_name, "FOCUS_MAX", timeout):
        prop = device.getNumber("FOCUS_MAX")
        if prop:
            for w in prop:
                log.info(f"  FOCUS_MAX: {w.getValue()}")

    # FOCUS_MOTION
    if wait_for_property(client, device_name, "FOCUS_MOTION", timeout):
        prop = device.getSwitch("FOCUS_MOTION")
        if prop:
            for w in prop:
                log.info(f"  FOCUS_MOTION: {w.getName()} = {w.getStateAsString()}")

    log.info(f"{'='*60}")


def main():
    parser = argparse.ArgumentParser(description="PocketScope INDI test client")
    parser.add_argument("--host", default="localhost",
                        help="INDI server hostname/IP (default: localhost)")
    parser.add_argument("--port", type=int, default=7624,
                        help="INDI server port (default: 7624)")
    parser.add_argument("--device", default=None,
                        help="Device name (auto-detects first PocketScope CCD device if omitted)")
    parser.add_argument("--focus-device", default="PocketScope Focuser",
                        help="Focuser device name (default: PocketScope Focuser)")
    parser.add_argument("--connect", action="store_true",
                        help="Connect to the device (send CONNECTION)")
    parser.add_argument("--capture", type=float, default=None, metavar="SEC",
                        help="Capture an image with this exposure time (seconds)")
    parser.add_argument("--output", "-o", default="capture.fits",
                        help="Output FITS path (default: capture.fits)")
    parser.add_argument("--focus-abs", type=int, default=None, metavar="POS",
                        help="Set absolute focus position")
    parser.add_argument("--focus-rel", type=int, default=None, metavar="STEPS",
                        help="Move focus relative (positive steps in current direction)")
    parser.add_argument("--focus-info", action="store_true",
                        help="Print focuser properties (position, range, direction)")
    parser.add_argument("--wait", type=float, default=3,
                        help="Seconds to wait for device discovery (default: 3)")
    args = parser.parse_args()

    # Focus commands imply --connect
    has_focus_action = (args.focus_abs is not None or args.focus_rel is not None
                        or args.focus_info)
    if has_focus_action:
        args.connect = True

    client = PocketScopeClient()
    client.setServer(args.host, args.port)

    log.info(f"Connecting to INDI server at {args.host}:{args.port}...")
    if not client.connectServer():
        log.error(f"Cannot connect to {args.host}:{args.port}")
        log.error("Is the PocketScope app running with the INDI server started?")
        sys.exit(1)

    # Discover devices
    device_names = list_devices(client, wait=args.wait)
    if not device_names:
        sys.exit(1)

    # Pick CCD device for capture/connect
    target = args.device
    if target is None:
        # Auto-detect first PocketScope device
        ps_devices = [n for n in device_names if n.startswith("PocketScope")]
        if ps_devices:
            target = ps_devices[0]
            log.info(f"Auto-selected device: {target}")
        else:
            target = device_names[0]
            log.info(f"No PocketScope device found, using first device: {target}")

    # Connect if requested (or if capture requested, which implies connect)
    if args.connect or args.capture is not None:
        if not connect_device(client, target):
            sys.exit(1)

    # Capture if requested
    if args.capture is not None:
        if not capture_image(client, target, args.capture, args.output):
            sys.exit(1)

    # Focus commands target the focuser device
    if has_focus_action:
        focus_target = args.focus_device
        # Connect the focuser device if it's different from the CCD target
        if focus_target != target:
            if not connect_device(client, focus_target):
                log.error(f"Could not connect to focuser device: {focus_target}")
                sys.exit(1)

        if args.focus_info:
            focus_info(client, focus_target)

        if args.focus_abs is not None:
            if not focus_move(client, focus_target, "ABS_FOCUS_POSITION",
                              "FOCUS_ABSOLUTE_POSITION", args.focus_abs):
                sys.exit(1)

        if args.focus_rel is not None:
            if not focus_move(client, focus_target, "REL_FOCUS_POSITION",
                              "FOCUS_RELATIVE_POSITION", args.focus_rel):
                sys.exit(1)

    # If no action requested, just list and exit
    if not args.connect and args.capture is None and not has_focus_action:
        log.info("Done. Use --connect and/or --capture to interact with devices.")

    # Keep alive briefly to catch any trailing messages
    time.sleep(1)
    client.disconnectServer()
    log.info("Disconnected.")


if __name__ == "__main__":
    main()
