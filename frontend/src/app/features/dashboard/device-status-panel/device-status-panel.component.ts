import { Component, OnInit, ChangeDetectorRef, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
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
  imports: [CommonModule],
  templateUrl: './device-status-panel.component.html',
  styleUrl: './device-status-panel.component.scss'
})
export class DeviceStatusPanelComponent implements OnInit {
  private statusService = inject(StatusService);
  private authService = inject(AuthService);
  private cdr = inject(ChangeDetectorRef);

  devices: DeviceCard[] = [
    { deviceType: 'DRIVE', label: 'External Drive', endpoint: '/api/status/check-drive', status: null, sseLog: [], sseExpanded: false, checking: false },
    { deviceType: 'IPHONE', label: 'iPhone', endpoint: '/api/status/check-iphone', status: null, sseLog: [], sseExpanded: false, checking: false },
    { deviceType: 'ICLOUD', label: 'iCloud', endpoint: '/api/status/check-icloud', status: null, sseLog: [], sseExpanded: false, checking: false },
  ];

  loadingStatuses = false;

  ngOnInit(): void {
    this.loadStatuses();
  }

  loadStatuses(): void {
    this.loadingStatuses = true;
    this.statusService.getDeviceStatuses().subscribe({
      next: (statuses) => {
        for (const s of statuses) {
          const device = this.devices.find(d => d.deviceType === s.deviceType);
          if (device) device.status = s;
        }
        this.loadingStatuses = false;
      },
      error: () => {
        this.loadingStatuses = false;
      }
    });
  }

  statusBadgeClass(connected: boolean): string {
    return connected ? 'connected' : 'disconnected';
  }

  async recheckDevice(device: DeviceCard): Promise<void> {
    device.checking = true;
    device.sseLog = [];
    device.sseExpanded = true;
    this.cdr.detectChanges();

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
              device.sseLog = [...device.sseLog, logEntry];

              if (event.terminal) {
                device.status = {
                  id: '',
                  deviceType: device.deviceType,
                  status: event.status ?? '',
                  connected: event.status === 'CONNECTED',
                  lastCheckedAt: new Date().toISOString(),
                  details: event.details ?? ''
                };
              }
            } catch { /* ignore parse errors */ }
            this.cdr.detectChanges();
          }
        }
      }
    } catch (err) {
      device.sseLog = [...device.sseLog, 'Error: ' + String(err)];
    } finally {
      device.checking = false;
      this.cdr.detectChanges();
    }
  }
}
