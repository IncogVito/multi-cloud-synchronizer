import '@angular/compiler';
import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { Store } from '@ngxs/store';
import { provideRouter } from '@angular/router';
import { of, Subject } from 'rxjs';
import { vi } from 'vitest';
import { SyncSectionComponent } from './sync-section.component';
import { AccountSessionService } from '../../../core/services/account-session.service';
import { SyncService } from '../../../core/services/sync.service';
import { DiskSetupService } from '../../../core/services/disk-setup.service';
import { AccountResponse } from '../../../core/api/generated/model/accountResponse';
import { DeviceStatusResponse } from '../../../core/api/generated/model/deviceStatusResponse';

function account(id: string, overrides: Partial<AccountResponse> = {}): AccountResponse {
  return {
    id,
    appleId: `${id}@icloud.com`,
    displayName: id,
    hasActiveSession: true,
    ...overrides,
  } as AccountResponse;
}

const icloudConnected = [
  { deviceType: 'EXTERNAL_DRIVE', connected: true } as DeviceStatusResponse,
  { deviceType: 'ICLOUD', connected: true } as DeviceStatusResponse,
];

interface SetupOpts {
  accounts?: AccountResponse[];
  activeAccountId?: string | null;
  devices?: DeviceStatusResponse[];
}

async function setup(opts: SetupOpts = {}) {
  const devicesSignal = signal<DeviceStatusResponse[]>(opts.devices ?? icloudConnected);
  const accountsSignal = signal<AccountResponse[]>(opts.accounts ?? []);

  // selectSignal is called in field order: DevicesState.devices, then AccountsState.accounts
  const signals = [devicesSignal, accountsSignal];
  let i = 0;
  const mockStore = { selectSignal: vi.fn(() => signals[i++ % signals.length]) };

  const activeSignal = signal<string | null>(opts.activeAccountId ?? null);
  const mockSession = { activeAccountId: activeSignal.asReadonly() };

  const mockSync = {
    syncProgress$: new Subject<unknown>(),
    resumeIfActive: vi.fn(),
    startSync: vi.fn(() => of({})),
    confirmSync: vi.fn(() => of({})),
    cancelSync: vi.fn(() => of({})),
    reset: vi.fn(),
  };

  const mockDiskSetup = { getStatus: vi.fn(() => of(null)) };

  await TestBed.configureTestingModule({
    imports: [SyncSectionComponent],
    providers: [
      provideRouter([]),
      { provide: Store, useValue: mockStore },
      { provide: AccountSessionService, useValue: mockSession },
      { provide: SyncService, useValue: mockSync },
      { provide: DiskSetupService, useValue: mockDiskSetup },
    ],
  }).compileComponents();

  const fixture = TestBed.createComponent(SyncSectionComponent);
  fixture.detectChanges();

  return { fixture, component: fixture.componentInstance, mockSync, accountsSignal, activeSignal };
}

describe('SyncSectionComponent — active account selection', () => {
  it('primaryAccount resolves to the user-selected account, not the first in the list', async () => {
    const { component } = await setup({
      accounts: [account('acc-1'), account('acc-2')],
      activeAccountId: 'acc-2',
    });

    expect(component.primaryAccount()?.id).toBe('acc-2');
  });

  it('primaryAccount is null when no active account id is set (no fallback)', async () => {
    const { component } = await setup({
      accounts: [account('acc-1'), account('acc-2')],
      activeAccountId: null,
    });

    expect(component.primaryAccount()).toBeNull();
  });

  it('startSync syncs the active account, not account 1', async () => {
    const { component, mockSync } = await setup({
      accounts: [account('acc-1'), account('acc-2')],
      activeAccountId: 'acc-2',
    });

    component.startSync();

    expect(mockSync.startSync).toHaveBeenCalledWith('acc-2', 'ICLOUD');
  });

  it('startSync throws for ICLOUD when there is no active account', async () => {
    const { component, mockSync } = await setup({
      accounts: [account('acc-1')],
      activeAccountId: null,
    });

    expect(() => component.startSync()).toThrow();
    expect(mockSync.startSync).not.toHaveBeenCalled();
  });
});

describe('SyncSectionComponent — error reporting', () => {
  it('errorDetail surfaces the backend error message', async () => {
    const { component } = await setup();
    component.activeProgress.set({ phase: 'ERROR', errorMessage: 'iCloud rate limited' } as any);

    expect(component.errorDetail()).toBe('iCloud rate limited');
  });

  it('errorDetail falls back when no message is provided', async () => {
    const { component } = await setup();
    component.activeProgress.set({ phase: 'ERROR' } as any);

    expect(component.errorDetail()).toBe('Nie udało się ustalić przyczyny błędu.');
  });
});

describe('SyncSectionComponent — scan label is provider-aware', () => {
  it('shows "Skanowanie iCloud" when syncing iCloud', async () => {
    const { component } = await setup();
    component.selectedProvider.set('ICLOUD');
    component.activeProgress.set({ phase: 'FETCHING_METADATA', metadataFetched: 5, totalOnCloud: 10 } as any);

    expect(component.progressTitle()).toBe('Skanowanie iCloud');
  });

  it('shows "Skanowanie iPhone" when syncing iPhone', async () => {
    const { component } = await setup();
    component.selectedProvider.set('IPHONE');
    component.activeProgress.set({ phase: 'FETCHING_METADATA', metadataFetched: 5, totalOnCloud: 10 } as any);

    expect(component.progressTitle()).toBe('Skanowanie iPhone');
  });
});
