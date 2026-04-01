import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { StatusService } from '../../../core/api/generated/status/status.service';
import { AuthService } from '../../../core/services/auth.service';
import { DeviceStatusResponse } from '../../../core/api/generated/model/deviceStatusResponse';

export interface DeviceCard {
  deviceType: string;
  label: string;
  endpoint: string;
  status: DeviceStatusResponse | null;
  sseLog: string[];
  sseExpanded: boolean;
  checking: boolean;
}

@Component({
  selector: 'app-device-status-panel',
  standalone: true,
  imports: [DatePipe],
  templateUrl: './device-status-panel.component.html',
  styleUrl: './device-status-panel.component.scss'
})
export class DeviceStatusPanelComponent implements OnInit {
  private statusService = inject(StatusService);
  private authService = inject(AuthService);

  devices = signal<DeviceCard[]>([
    { deviceType: 'DRIVE', label: 'External Drive', endpoint: '/api/status/check-drive', status: null, sseLog: [], sseExpanded: false, checking: false },
    { deviceType: 'IPHONE', label: 'iPhone', endpoint: '/api/status/check-iphone', status: null, sseLog: [], sseExpanded: false, checking: false },
    { deviceType: 'ICLOUD', label: 'iCloud', endpoint: '/api/status/check-icloud', status: null, sseLog: [], sseExpanded: false, checking: false },
  ]);

  loadingStatuses = signal(false);

  ngOnInit(): void {
    this.loadStatuses();
  }

  loadStatuses(): void {
    this.loadingStatuses.set(true);
    this.statusService.getDeviceStatuses().subscribe({
      next: (statuses) => {
        this.devices.update(devs => devs.map(d => {
          const s = statuses.find(s => s.deviceType === d.deviceType);
          return s ? { ...d, status: s } : d;
        }));
        this.loadingStatuses.set(false);
      },
      error: () => {
        this.loadingStatuses.set(false);
      }
    });
  }

  statusBadgeClass(connected: boolean): string {
    return connected ? 'connected' : 'disconnected';
  }

  toggleSseExpanded(deviceType: string): void {
    this.devices.update(devs => devs.map(d =>
      d.deviceType === deviceType ? { ...d, sseExpanded: !d.sseExpanded } : d
    ));
  }

  async recheckDevice(device: DeviceCard): Promise<void> {
    const deviceType = device.deviceType;

    this.devices.update(devs => devs.map(d =>
      d.deviceType === deviceType ? { ...d, checking: true, sseLog: [], sseExpanded: true } : d
    ));

    const creds = this.authService.getCredentials();
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (creds) headers['Authorization'] = `Basic ${creds.encoded}`;

    try {
      const response = await fetch(device.endpoint, { method: 'POST', headers });
      const reader = response.body!.getReader();
      const decoder = new TextDecoder();
      let buffer = '';

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() ?? '';
        for (const line of lines) {
          if (line.startsWith('data:')) {
            try {
              const event = JSON.parse(line.slice(5).trim());
              const logEntry = event.stepDescription
                ? `[${event.status ?? ''}] ${event.stepDescription}`
                : JSON.stringify(event);

              this.devices.update(devs => devs.map(d => {
                if (d.deviceType !== deviceType) return d;
                const updated: DeviceCard = { ...d, sseLog: [...d.sseLog, logEntry] };
                if (event.terminal) {
                  updated.status = {
                    id: '',
                    deviceType,
                    status: event.status ?? '',
                    connected: event.status === 'CONNECTED',
                    lastCheckedAt: new Date().toISOString(),
                    details: event.details ?? ''
                  };
                }
                return updated;
              }));
            } catch { /* ignore parse errors */ }
          }
        }
      }
    } catch (err) {
      this.devices.update(devs => devs.map(d =>
        d.deviceType === deviceType ? { ...d, sseLog: [...d.sseLog, 'Error: ' + String(err)] } : d
      ));
    } finally {
      this.devices.update(devs => devs.map(d =>
        d.deviceType === deviceType ? { ...d, checking: false } : d
      ));
    }
  }
}
