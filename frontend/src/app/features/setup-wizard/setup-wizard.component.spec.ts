import '@angular/compiler';
import { vi, describe, it, expect, beforeEach, afterEach } from 'vitest';
import { of } from 'rxjs';
import { createEnvironmentInjector, EnvironmentInjector, runInInjectionContext } from '@angular/core';
import { Router } from '@angular/router';
import { SetupWizardComponent } from './setup-wizard.component';
import { AccountService } from '../../core/services/account.service';
import { AccountSessionService } from '../../core/services/account-session.service';
import { AppContextService } from '../../core/services/app-context.service';
import { DiskIndexingService } from '../../core/services/disk-indexing.service';
import { SetupWizardService } from '../../core/services/setup-wizard.service';

describe('SetupWizardComponent — persist sync folder at folder pick', () => {
  let injector: EnvironmentInjector;
  let saveSyncConfig: ReturnType<typeof vi.fn>;

  function build(): SetupWizardComponent {
    return runInInjectionContext(injector, () => new SetupWizardComponent());
  }

  beforeEach(() => {
    saveSyncConfig = vi.fn().mockReturnValue(of({ success: true }));
    injector = createEnvironmentInjector([
      { provide: AccountService, useValue: { listAccounts: vi.fn().mockReturnValue(of([])) } },
      { provide: AccountSessionService, useValue: { activeAccountId: vi.fn().mockReturnValue('acc-1') } },
      { provide: AppContextService, useValue: { set: vi.fn().mockReturnValue(of({})) } },
      { provide: DiskIndexingService, useValue: { start: vi.fn().mockReturnValue(of({})) } },
      { provide: SetupWizardService, useValue: { saveSyncConfig } },
      { provide: Router, useValue: { navigate: vi.fn() } },
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
});
