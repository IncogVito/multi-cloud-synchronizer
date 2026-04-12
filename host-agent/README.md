# CloudSync Host Agent

Python asyncio daemon listening on a Unix Domain Socket. Replaces the
brittle named-FIFO bridge (`pipe-daemon.sh` / `pipe-call.sh`) with a proper
JSON-over-socket protocol.

## Requirements

- Python 3.9+
- `ifuse` and `libimobiledevice-utils` for iPhone actions:
  ```
  sudo apt install ifuse libimobiledevice-utils
  ```
- `blkid`, `lsblk`, `mount`, `umount` — standard on most Linux systems

## Installation

```bash
# Clone / copy the host-agent/ directory to the server, then:
cd host-agent
sudo ./install.sh
```

`install.sh` is idempotent — run it again to update files and restart the
service.

## Verify

```bash
systemctl status cloudsync-host-agent
```

## Manual test

```bash
echo '{"action":"list_disks","params":{}}' \
  | socat - UNIX-CONNECT:/run/cloudsync-host-agent/agent.sock
```

Expected response:
```json
{"ok": true, "data": [...]}
```

## Logs

```bash
journalctl -u cloudsync-host-agent -f
```

## Protocol

One connection = one request/response (newline-delimited JSON).

**Request:**
```json
{"action": "mount_drive", "params": {"device": "/dev/sdb1", "mount_point": "/mnt/external-drive"}}
```

**Success response:**
```json
{"ok": true, "data": {"mounted": true, "device": "/dev/sdb1", "mount_point": "/mnt/external-drive", "message": "..."}}
```

**Error response:**
```json
{"ok": false, "error": "Device not found", "code": "MOUNT_FAILED"}
```

## Supported actions

| Action               | Params                                      | Needs root |
|----------------------|---------------------------------------------|------------|
| `check_drive`        | `mount_path` (optional)                     | no         |
| `list_disks`         | —                                           | no         |
| `mount_drive`        | `device`, `mount_point` (optional)          | yes        |
| `unmount_drive`      | `mount_point` (optional)                    | yes        |
| `read_device_id`     | `device`                                    | yes        |
| `detect_iphone`      | —                                           | no         |
| `iphone_list_devices`| —                                           | no         |
| `iphone_check_trust` | `udid` (optional)                           | no         |
| `iphone_get_info`    | `udid` (optional)                           | no         |
| `iphone_mount`       | `mount_path` (optional)                     | no (ifuse) |
| `iphone_unmount`     | `mount_path` (optional)                     | no (fuse)  |

## Configuration

Environment variables (set via systemd unit override or `/etc/environment`):

| Variable              | Default                                    |
|-----------------------|--------------------------------------------|
| `SOCKET_PATH`         | `/run/cloudsync-host-agent/agent.sock`     |
| `EXTERNAL_DRIVE_PATH` | `/mnt/external-drive`                      |
| `IPHONE_MOUNT_PATH`   | `/mnt/iphone`                              |
| `CONTAINER_UID`       | `1000`                                     |
| `CONTAINER_GID`       | `1000`                                     |
| `REQUEST_TIMEOUT`     | `60`                                       |
