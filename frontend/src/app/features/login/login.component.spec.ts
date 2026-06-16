import '@angular/compiler';
import { vi, describe, it, expect, beforeEach, afterEach } from 'vitest';
import { of } from 'rxjs';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { LoginComponent } from './login.component';
import { AuthService } from '../../core/services/auth.service';
import { AccountService } from '../../core/services/account.service';
import { AppContextService } from '../../core/services/app-context.service';
import { AccountSessionService } from '../../core/services/account-session.service';

describe('LoginComponent', () => {
  let login: ReturnType<typeof vi.fn>;
  let listAccounts: ReturnType<typeof vi.fn>;
  let submitTwoFa: ReturnType<typeof vi.fn>;
  let setActiveAccount: ReturnType<typeof vi.fn>;
  let setCredentials: ReturnType<typeof vi.fn>;
  let navigate: ReturnType<typeof vi.fn>;
  let hasContext: boolean;

  function create(): LoginComponent {
    login = vi.fn().mockReturnValue(
      of({ requires2fa: false, accountId: 'acc-1', sessionId: 'sess-1' })
    );
    listAccounts = vi.fn().mockReturnValue(of([]));
    submitTwoFa = vi.fn().mockReturnValue(of({ authenticated: true }));
    setActiveAccount = vi.fn();
    setCredentials = vi.fn();
    navigate = vi.fn();

    TestBed.configureTestingModule({
      providers: [
        { provide: AccountService, useValue: { login, listAccounts, submitTwoFa } },
        { provide: AuthService, useValue: { setCredentials } },
        {
          provide: AppContextService,
          useValue: { load: vi.fn().mockReturnValue(of({})), hasContext: () => hasContext },
        },
        { provide: AccountSessionService, useValue: { setActiveAccount, activeAccountId: () => null } },
        { provide: Router, useValue: { navigate } },
      ],
    });
    return TestBed.runInInjectionContext(() => new LoginComponent());
  }

  beforeEach(() => {
    hasContext = true;
    TestBed.resetTestingModule();
    vi.useFakeTimers();
  });

  afterEach(() => vi.useRealTimers());

  it('stores the active account id after a successful login so accountGuard passes', () => {
    const component = create();
    component.selectedAccount.set({
      id: 'acc-1', displayName: 'A', appleId: 'a@b.c',
      hasActiveSession: false, lastSyncAt: '', createdAt: '',
    } as any);
    component.loginForm.get('password')?.setValue('pw');

    component.onLoginSubmit();
    vi.runAllTimers();

    expect(setActiveAccount).toHaveBeenCalledWith('acc-1');
    expect(navigate).toHaveBeenCalledWith(['/dashboard']);
  });

  it('stores the active account id after 2FA verification', () => {
    const component = create();
    component.selectedAccount.set({
      id: 'acc-1', displayName: 'A', appleId: 'a@b.c',
      hasActiveSession: false, lastSyncAt: '', createdAt: '',
    } as any);
    component.loginForm.get('password')?.setValue('pw');
    component.currentAccountId.set('acc-1');
    component.sessionId.set('sess-1');
    component.twoFaForm.get('code')?.setValue('1234');

    component.onTwoFaSubmit();
    vi.runAllTimers();

    expect(setActiveAccount).toHaveBeenCalledWith('acc-1');
    expect(navigate).toHaveBeenCalledWith(['/dashboard']);
  });
});
