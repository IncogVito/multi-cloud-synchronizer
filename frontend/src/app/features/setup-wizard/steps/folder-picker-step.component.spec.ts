import '@angular/compiler';
import { vi, describe, it, expect, beforeEach, afterEach } from 'vitest';
import { of } from 'rxjs';
import { createEnvironmentInjector, EnvironmentInjector, runInInjectionContext } from '@angular/core';
import { FolderPickerStepComponent } from './folder-picker-step.component';
import { SetupWizardService } from '../../../core/services/setup-wizard.service';

describe('FolderPickerStepComponent — scan of folder without images', () => {
  let injector: EnvironmentInjector;
  let scan: ReturnType<typeof vi.fn>;

  function build(): FolderPickerStepComponent {
    return runInInjectionContext(injector, () => new FolderPickerStepComponent());
  }

  beforeEach(() => {
    // Backend omits `byExtension` when no images were found (response: {totalFiles, deepestLevel}).
    scan = vi.fn().mockReturnValue(of({ totalFiles: 0, deepestLevel: 0 } as any));
    injector = createEnvironmentInjector([
      { provide: SetupWizardService, useValue: { browse: vi.fn(), scan } },
    ]);
  });

  afterEach(() => {
    injector.destroy();
    vi.restoreAllMocks();
  });

  it('does not get stuck in "scanning" when byExtension is missing (root bug regression)', () => {
    const cmp = build();
    cmp.selectFolder({ name: 'Sync', path: '/mnt/drive/Sync', childCount: 0 } as any);

    // Must finish scanning so the "Wybierz ten folder" button re-enables.
    expect(cmp.scanLoading()).toBe(false);
    expect(cmp.scanResult()).not.toBeNull();
    expect(cmp.scanResult()!.totalFiles).toBe(0);
  });
});
