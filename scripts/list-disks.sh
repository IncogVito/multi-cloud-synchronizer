#!/bin/bash
# List available external block devices (skips sda/system disk).
# Output: JSON from lsblk filtered to non-system disks.

lsblk -J -o NAME,SIZE,TYPE,MOUNTPOINT,LABEL,VENDOR,MODEL 2>/dev/null | \
  python3 -c "
import json, sys

data = json.load(sys.stdin)
result = []

def collect(devices, parent=None):
    for dev in devices:
        name = dev.get('name', '')
        dtype = dev.get('type', '')
        # Skip sda (system disk) and loop/rom devices
        if name.startswith('sda') or dtype in ('loop', 'rom'):
            if 'children' in dev:
                collect(dev['children'], parent=name)
            continue
        if dtype in ('disk', 'part'):
            result.append({
                'name': name,
                'path': '/dev/' + name,
                'size': dev.get('size', ''),
                'type': dtype,
                'mountpoint': dev.get('mountpoint'),
                'label': dev.get('label'),
                'vendor': (dev.get('vendor') or '').strip(),
                'model': (dev.get('model') or '').strip(),
            })
        if 'children' in dev:
            collect(dev['children'], parent=name)

collect(data.get('blockdevices', []))
print(json.dumps(result))
"
