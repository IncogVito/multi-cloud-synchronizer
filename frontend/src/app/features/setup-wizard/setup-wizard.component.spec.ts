import '@angular/compiler';
import { vi, describe, it, expect, beforeEach, afterEach } from 'vitest';
import { of } from 'rxjs';
import { createEnvironmentInjector, EnvironmentInjector, runInInjectionContext } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { SetupWizardComponent } from './setup-wizard.component';
import { AccountService } from '../../core/services/account.service';
import { AccountSessionService } from '../../core/services/account-session.service';
import { AppContextService } from '../../core/services/app-context.service';
import { DiskIndexingService } from '../../core/services/disk-indexing.service';
import { SetupWizardService } from '../../core/services/setup-wizard.service';

describe('SetupWizardComponent — persist sync folder at folder pick', () => {
  let injector: EnvironmentInjector;
  let saveSyncConfig: ReturnType<typeof vi.fn>;
  let queryParams: Record<string, string>;
  let context: () => { storageDeviceId: string; storageDeviceLabel: string | null } | null;

  function build(): SetupWizardComponent {
    return runInInjectionContext(injector, () => new SetupWizardComponent());
  }

  beforeEach(() => {
    saveSyncConfig = vi.fn().mockReturnValue(of({ success: true }));
    queryParams = {};
    context = () => null;
    injector = createEnvironmentInjector([
      { provide: AccountService, useValue: { listAccounts: vi.fn().mockReturnValue(of([{ id: 'acc-1' }])) } },
      { provide: AccountSessionService, useValue: { activeAccountId: vi.fn().mockReturnValue('acc-1') } },
      { provide: AppContextService, useValue: { set: vi.fn().mockReturnValue(of({})), context: () => context() } },
      { provide: DiskIndexingService, useValue: { start: vi.fn().mockReturnValue(of({})) } },
      { provide: SetupWizardService, useValue: { saveSyncConfig } },
      { provide: Router, useValue: { navigate: vi.fn() } },
      { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: { get: (k: string) => queryParams[k] ?? null } } } },
    ]);
  });

  afterEach(() => {
    injector.destroy();
    vi.restoreAllMocks();
  });

  it('calls saveSyncConfig with the active account when a folder is picked', () => {
    const cmp = build();
    cmp.onFolderSelected({ path: '/mnt/drive/folder-a', scanResult: { totalFiles: 42 } as any });

    expect(saveSyncConfig).toHaveBeenCalledTimes(1);
    const [accountId, config] = saveSyncConfig.mock.calls[0];
    expect(accountId).toBe('acc-1');
    expect(config.syncFolderPath).toBe('/mnt/drive/folder-a');
  });

  it('persists even when the picked folder is empty (root bug regression)', () => {
    const cmp = build();
    cmp.onFolderSelected({ path: '/mnt/drive/empty', scanResult: { totalFiles: 0 } as any });

    expect(saveSyncConfig).toHaveBeenCalledTimes(1);
    expect(saveSyncConfig.mock.calls[0][1].syncFolderPath).toBe('/mnt/drive/empty');
  });

  it('on action=change-folder, starts at the folder step with the device pre-filled from context', () => {
    queryParams = { action: 'change-folder' };
    context = () => ({ storageDeviceId: 'dev-9', storageDeviceLabel: 'My Disk' });

    const cmp = build();
    cmp.ngOnInit();

    // Skips the disk-confirm step (1) and jumps straight to folder picking (2).
    expect(cmp.currentStep()).toBe(2);

    // Folder pick must persist with the device taken from the existing context.
    cmp.onFolderSelected({ path: '/mnt/drive/new', scanResult: { totalFiles: 5 } as any });
    expect(saveSyncConfig).toHaveBeenCalledTimes(1);
    expect(saveSyncConfig.mock.calls[0][1].storageDeviceId).toBe('dev-9');
  });
});
