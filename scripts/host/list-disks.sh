#!/bin/bash
# List eligible external block devices for mounting.
# Filters out:
#  - system disk (sda) and loop/rom devices
#  - Microsoft virtual disks used by WSL (Msft Virtual Disk vendor)
#  - devices already used by the system (SWAP, /, /boot, /mnt/wslg*)
# Prefers partitions over their parent disks when a partition exists.
# If a disk has no eligible partitions, the disk itself is listed.
# Output: JSON array.

lsblk -J -o NAME,SIZE,TYPE,MOUNTPOINT,LABEL,VENDOR,MODEL 2>/dev/null | \
  python3 -c "
import json, sys

SYSTEM_MOUNTS = ('[SWAP]', '/', '/boot')

def is_system_mount(mp):
    if not mp:
        return False
    if mp in SYSTEM_MOUNTS:
        return True
    if mp.startswith('/mnt/wslg'):
        return True
    if mp.startswith('/boot'):
        return True
    return False

def has_system_mount_recursive(dev):
    \"\"\"Return True if this device or any descendant (LVM, etc.) has a system mount.\"\"\"
    if is_system_mount(dev.get('mountpoint')):
        return True
    for child in (dev.get('children') or []):
        if has_system_mount_recursive(child):
            return True
    return False

def is_virtual(vendor, model):
    v = (vendor or '').strip().lower()
    m = (model or '').strip().lower()
    return 'msft' in v or 'virtual disk' in m

def to_entry(dev, dtype):
    return {
        'name': dev.get('name', ''),
        'path': '/dev/' + dev.get('name', ''),
        'size': dev.get('size', ''),
        'type': dtype,
        'mountpoint': dev.get('mountpoint'),
        'label': dev.get('label'),
        'vendor': (dev.get('vendor') or '').strip(),
        'model': (dev.get('model') or '').strip(),
    }

data = json.load(sys.stdin)
result = []

for dev in data.get('blockdevices', []):
    dtype = dev.get('type', '')
    if dtype in ('loop', 'rom'):
        continue
    if is_virtual(dev.get('vendor'), dev.get('model')):
        continue
    # Skip entire disk if it or any descendant hosts a system mount
    if has_system_mount_recursive(dev):
        continue

    children = dev.get('children') or []
    eligible_parts = [
        c for c in children
        if c.get('type') == 'part' and not has_system_mount_recursive(c)
    ]

    if eligible_parts:
        for c in eligible_parts:
            entry = to_entry(c, 'part')
            # Inherit vendor/model from parent for display
            if not entry['vendor']:
                entry['vendor'] = (dev.get('vendor') or '').strip()
            if not entry['model']:
                entry['model'] = (dev.get('model') or '').strip()
            result.append(entry)
    elif dtype == 'disk':
        result.append(to_entry(dev, 'disk'))

print(json.dumps(result))
"
