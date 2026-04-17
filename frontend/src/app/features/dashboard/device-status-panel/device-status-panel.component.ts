import { Component, OnInit, inject, signal, computed } from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { Store } from '@ngxs/store';
import { AuthService } from '../../../core/services/auth.service';
import { StatusService } from '../../../core/api/generated/status/status.service';
import { DeviceStatusResponse } from '../../../core/api/generated/model/deviceStatusResponse';
import { DevicesState } from '../../../state/devices/devices.state';
import { LoadDevices, UpdateDevice } from '../../../state/devices/devices.actions';

export interface DeviceCard {
  deviceType: string;
  label: string;
  endpoint: string;
  status: DeviceStatusResponse | null;
  sseLog: string[];
  sseExpanded: boolean;
  checking: boolean;
  unmounting: boolean;
}

/** Metadata that doesn't belong in the global store — per-device UI state only. */
interface DeviceUiState {
  sseLog: string[];
  sseExpanded: boolean;
  checking: boolean;
  unmounting: boolean;
}

const DEVICE_DEFAULTS: Record<string, Pick<DeviceCard, 'label' | 'endpoint'>> = {
  EXTERNAL_DRIVE: { label: 'External Drive', endpoint: '/api/status/check-drive' },
  IPHONE:         { label: 'iPhone',          endpoint: '/api/status/check-iphone' },
  ICLOUD:         { label: 'iCloud',          endpoint: '/api/status/check-icloud' },
};

@Component({
  selector: 'app-device-status-panel',
  standalone: true,
  imports: [DatePipe, RouterLink],
  templateUrl: './device-status-panel.component.html',
  styleUrl: './device-status-panel.component.scss'
})
export class DeviceStatusPanelComponent implements OnInit {
  private store = inject(Store);
  private statusService = inject(StatusService);
  private authService = inject(AuthService);

  loadingStatuses = signal(false);

  /** Device statuses come from the global DevicesState, which is polled automatically. */
  private storeDevices = this.store.selectSignal(DevicesState.devices);

  /** Per-device UI state (SSE logs, expand toggles, spinners) — local to this component. */
  private deviceUiStates = signal<Map<string, DeviceUiState>>(new Map([
    ['EXTERNAL_DRIVE', { sseLog: [], sseExpanded: false, checking: false, unmounting: false }],
    ['IPHONE',         { sseLog: [], sseExpanded: false, checking: false, unmounting: false }],
    ['ICLOUD',         { sseLog: [], sseExpanded: false, checking: false, unmounting: false }],
  ]));

  /** Combined view model merging store status with local UI state. */
  devices = computed<DeviceCard[]>(() => {
    const statuses = this.storeDevices();
    const uiMap = this.deviceUiStates();

    return Object.entries(DEVICE_DEFAULTS).map(([deviceType, meta]) => {
      const status = statuses.find(s => s.deviceType === deviceType) ?? null;
      const ui = uiMap.get(deviceType) ?? { sseLog: [], sseExpanded: false, checking: false, unmounting: false };
      return { deviceType, label: meta.label, endpoint: meta.endpoint, status, ...ui };
    });
  });

  ngOnInit(): void {
    // DevicesState polling is started by DashboardComponent on init.
  }

  loadStatuses(): void {
    this.loadingStatuses.set(true);
    this.store.dispatch(new LoadDevices()).subscribe({
      next: () => this.loadingStatuses.set(false),
      error: () => this.loadingStatuses.set(false),
    });
  }

  statusBadgeClass(connected: boolean): string {
    return connected ? 'connected' : 'disconnected';
  }

  toggleSseExpanded(deviceType: string): void {
    this.updateUiState(deviceType, ui => ({ ...ui, sseExpanded: !ui.sseExpanded }));
  }

  unmountIPhone(): void {
    this.updateUiState('IPHONE', ui => ({ ...ui, unmounting: true }));
    this.statusService.unmountIPhone().subscribe({
      next: (result) => {
        if (result['unmounted']) {
          const currentStatus = this.storeDevices().find(d => d.deviceType === 'IPHONE');
          if (currentStatus) {
            this.store.dispatch(new UpdateDevice({ ...currentStatus, mounted: false }));
          }
        }
        this.updateUiState('IPHONE', ui => ({ ...ui, unmounting: false }));
      },
      error: () => {
        this.updateUiState('IPHONE', ui => ({ ...ui, unmounting: false }));
      }
    });
  }

  /**
   * Runs an SSE-based device check. Streams step events into the local sseLog,
   * then dispatches UpdateDevice to the store with the final status.
   *
   * TODO: Test that concurrent recheckDevice calls for the same deviceType
   * don't produce interleaved sseLog entries.
   * TODO: Test that the store status updates correctly after a CONNECTED terminal event.
   */
  async recheckDevice(device: DeviceCard): Promise<void> {
    const deviceType = device.deviceType;

    this.updateUiState(deviceType, ui => ({ ...ui, checking: true, sseLog: [], sseExpanded: true }));

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
            this.processSseEvent(deviceType, line.slice(5).trim());
          }
        }
      }
    } catch (err) {
      this.updateUiState(deviceType, ui => ({
        ...ui,
        sseLog: [...ui.sseLog, 'Error: ' + String(err)],
      }));
    } finally {
      this.updateUiState(deviceType, ui => ({ ...ui, checking: false }));
    }
  }

  private processSseEvent(deviceType: string, rawJson: string): void {
    try {
      const event = JSON.parse(rawJson);
      const logEntry = event.stepDescription
        ? `[${event.status ?? ''}] ${event.stepDescription}`
        : JSON.stringify(event);

      this.updateUiState(deviceType, ui => ({ ...ui, sseLog: [...ui.sseLog, logEntry] }));

      if (event.terminal) {
        const finalStatus: DeviceStatusResponse = {
          id: '',
          deviceType,
          status: event.status ?? '',
          connected: event.status === 'CONNECTED',
          lastCheckedAt: new Date().toISOString(),
          details: event.details ?? '',
          mounted: deviceType === 'IPHONE' && event.status === 'CONNECTED',
        };
        this.store.dispatch(new UpdateDevice(finalStatus));
      }
    } catch {
      // Ignore malformed SSE events
    }
  }

  private updateUiState(deviceType: string, updater: (current: DeviceUiState) => DeviceUiState): void {
    this.deviceUiStates.update(map => {
      const current = map.get(deviceType) ?? { sseLog: [], sseExpanded: false, checking: false, unmounting: false };
      const updated = new Map(map);
      updated.set(deviceType, updater(current));
      return updated;
    });
  }
}
