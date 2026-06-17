import '@angular/compiler';
import { vi, describe, it, expect, beforeEach, afterEach } from 'vitest';
import { of, throwError } from 'rxjs';
import { createEnvironmentInjector, EnvironmentInjector, runInInjectionContext } from '@angular/core';
import { Router, UrlTree } from '@angular/router';
import { syncFolderGuard } from './sync-folder.guard';
import { AccountSessionService } from '../services/account-session.service';
import { AccountService } from '../services/account.service';

describe('syncFolderGuard', () => {
  let loginTree: UrlTree;
  let wizardTree: UrlTree;
  let sessionMock: Pick<AccountSessionService, 'activeAccountId'>;
  let accountServiceMock: Pick<AccountService, 'getAccountStatus'>;
  let routerMock: Pick<Router, 'createUrlTree'>;
  let injector: EnvironmentInjector;

  beforeEach(() => {
    loginTree = { toString: () => '/login' } as unknown as UrlTree;
    wizardTree = { toString: () => '/setup/wizard' } as unknown as UrlTree;
    routerMock = {
      createUrlTree: vi.fn().mockImplementation((commands: any[]) =>
        commands?.[0] === '/login' ? loginTree : wizardTree),
    };
    sessionMock = { activeAccountId: vi.fn().mockReturnValue('acc-1') as any };
    accountServiceMock = { getAccountStatus: vi.fn() };
    injector = createEnvironmentInjector([
      { provide: AccountSessionService, useValue: sessionMock },
      { provide: AccountService, useValue: accountServiceMock },
      { provide: Router, useValue: routerMock },
    ]);
  });

  afterEach(() => {
    injector.destroy();
    vi.restoreAllMocks();
  });

  function run() {
    return runInInjectionContext(injector, () => syncFolderGuard({} as any, {} as any));
  }

  it('redirects to /login when no activeAccountId', () => {
    (sessionMock.activeAccountId as any) = vi.fn().mockReturnValue(null);
    const result = run();
    expect(result).toBe(loginTree);
    expect(routerMock.createUrlTree).toHaveBeenCalledWith(['/login']);
  });

  it('redirects to the wizard when active account has no syncFolderPath', () => {
    (accountServiceMock.getAccountStatus as any) = vi.fn().mockReturnValue(
      of({ id: 'acc-1', syncFolderPath: null }));
    let result: boolean | UrlTree | undefined;
    (run() as ReturnType<typeof of>).subscribe((val: boolean | UrlTree) => (result = val));
    expect(result).toBe(wizardTree);
    expect(routerMock.createUrlTree).toHaveBeenCalledWith(
      ['/setup/wizard'], { queryParams: { accountId: 'acc-1' } });
  });

  it('redirects to the wizard when syncFolderPath is empty string', () => {
    (accountServiceMock.getAccountStatus as any) = vi.fn().mockReturnValue(
      of({ id: 'acc-1', syncFolderPath: '' }));
    let result: boolean | UrlTree | undefined;
    (run() as ReturnType<typeof of>).subscribe((val: boolean | UrlTree) => (result = val));
    expect(result).toBe(wizardTree);
  });

  it('passes through when syncFolderPath is set', () => {
    (accountServiceMock.getAccountStatus as any) = vi.fn().mockReturnValue(
      of({ id: 'acc-1', syncFolderPath: '/mnt/drive/folder-a' }));
    let result: boolean | UrlTree | undefined;
    (run() as ReturnType<typeof of>).subscribe((val: boolean | UrlTree) => (result = val));
    expect(result).toBe(true);
  });

  it('redirects to /login when the status request fails', () => {
    (accountServiceMock.getAccountStatus as any) = vi.fn().mockReturnValue(
      throwError(() => new Error('boom')));
    let result: boolean | UrlTree | undefined;
    (run() as ReturnType<typeof of>).subscribe((val: boolean | UrlTree) => (result = val));
    expect(result).toBe(loginTree);
  });
});
