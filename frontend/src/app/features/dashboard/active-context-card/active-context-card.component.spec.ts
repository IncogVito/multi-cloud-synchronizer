import '@angular/compiler';
import { vi, describe, it, expect, beforeEach, afterEach } from 'vitest';
import { createEnvironmentInjector, EnvironmentInjector, runInInjectionContext } from '@angular/core';
import { Router } from '@angular/router';
import { ActiveContextCardComponent } from './active-context-card.component';
import { AppContextService } from '../../../core/services/app-context.service';

describe('ActiveContextCardComponent — change folder routing', () => {
  let injector: EnvironmentInjector;
  let navigate: ReturnType<typeof vi.fn>;

  function build(): ActiveContextCardComponent {
    return runInInjectionContext(injector, () => new ActiveContextCardComponent());
  }

  beforeEach(() => {
    navigate = vi.fn();
    injector = createEnvironmentInjector([
      { provide: AppContextService, useValue: { context: () => null } },
      { provide: Router, useValue: { navigate } },
    ]);
  });

  afterEach(() => {
    injector.destroy();
    vi.restoreAllMocks();
  });

  it('routes "change folder" to the per-account wizard so syncFolderPath is persisted', () => {
    const cmp = build();
    cmp.changeFolder();

    expect(navigate).toHaveBeenCalledTimes(1);
    const [commands, extras] = navigate.mock.calls[0];
    expect(commands).toEqual(['/setup/wizard']);
    expect(extras).toEqual({ queryParams: { action: 'change-folder' } });
  });
});
