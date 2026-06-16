import '@angular/compiler';
import { vi, describe, it, expect, beforeEach, afterEach } from 'vitest';
import { of } from 'rxjs';
import { createEnvironmentInjector, EnvironmentInjector, runInInjectionContext } from '@angular/core';
import { Router, UrlTree } from '@angular/router';
import { accountGuard } from './account.guard';
import { AccountSessionService } from '../services/account-session.service';

describe('accountGuard', () => {
  let loginTree: UrlTree;
  let sessionMock: Pick<AccountSessionService, 'activeAccountId' | 'validateSession'>;
  let routerMock: Pick<Router, 'createUrlTree'>;
  let injector: EnvironmentInjector;

  beforeEach(() => {
    loginTree = { toString: () => '/login' } as unknown as UrlTree;
    routerMock = { createUrlTree: vi.fn().mockReturnValue(loginTree) };
    sessionMock = {
      activeAccountId: vi.fn().mockReturnValue(null) as any,
      validateSession: vi.fn().mockReturnValue(of(false)),
    };
    injector = createEnvironmentInjector([
      { provide: AccountSessionService, useValue: sessionMock },
      { provide: Router, useValue: routerMock },
    ]);
  });

  afterEach(() => {
    injector.destroy();
    vi.restoreAllMocks();
  });

  function run() {
    return runInInjectionContext(injector, () => accountGuard({} as any, {} as any));
  }

  it('redirects to /login when no activeAccountId', () => {
    (sessionMock.activeAccountId as any) = vi.fn().mockReturnValue(null);
    const result = run();
    expect(result).toBe(loginTree);
    expect(routerMock.createUrlTree).toHaveBeenCalledWith(['/login']);
  });

  it('redirects to /login when session validation returns false', () => {
    (sessionMock.activeAccountId as any) = vi.fn().mockReturnValue('acc-1');
    (sessionMock.validateSession as any) = vi.fn().mockReturnValue(of(false));
    let result: boolean | UrlTree | undefined;
    (run() as ReturnType<typeof of>).subscribe((val: boolean | UrlTree) => (result = val));
    expect(result).toBe(loginTree);
  });

  it('passes through when session is valid', () => {
    (sessionMock.activeAccountId as any) = vi.fn().mockReturnValue('acc-1');
    (sessionMock.validateSession as any) = vi.fn().mockReturnValue(of(true));
    let result: boolean | UrlTree | undefined;
    (run() as ReturnType<typeof of>).subscribe((val: boolean | UrlTree) => (result = val));
    expect(result).toBe(true);
  });
});
