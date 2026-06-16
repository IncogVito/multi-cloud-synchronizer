import '@angular/compiler';
import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { Store } from '@ngxs/store';
import { Router } from '@angular/router';
import { of } from 'rxjs';
import { vi } from 'vitest';
import { AccountsPanelComponent } from './accounts-panel.component';
import { AccountSessionService } from '../../../core/services/account-session.service';
import { AccountsService } from '../../../core/api/generated/accounts/accounts.service';
import { AccountResponse } from '../../../core/api/generated/model/accountResponse';
import { AccountRemoved } from '../../../state/accounts/accounts.actions';

function account(id: string, overrides: Partial<AccountResponse> = {}): AccountResponse {
  return {
    id,
    appleId: `${id}@icloud.com`,
    displayName: id,
    hasActiveSession: true,
    ...overrides,
  } as AccountResponse;
}

interface SetupOpts {
  accounts?: AccountResponse[];
  activeAccountId?: string | null;
}

async function setup(opts: SetupOpts = {}) {
  const accountsSignal = signal<AccountResponse[]>(opts.accounts ?? []);
  const loadingSignal = signal(false);
  const errorSignal = signal<string | null>(null);

  const mockStore = {
    selectSignal: vi.fn((selector: unknown) => {
      // selectSignal is called for accounts, loading, error in field order
      return accountsSignal;
    }),
    dispatch: vi.fn(),
  };
  // distinguish the three selectors by call order in ngOnInit fields
  const signals = [accountsSignal, loadingSignal, errorSignal];
  let i = 0;
  mockStore.selectSignal = vi.fn(() => signals[i++ % 3]);

  const activeSignal = signal<string | null>(opts.activeAccountId ?? null);
  const mockSession = {
    activeAccountId: activeSignal.asReadonly(),
    clearSession: vi.fn(() => activeSignal.set(null)),
  };

  const mockAccountsApi = {
    logout: vi.fn(() => of(undefined)),
    deleteAccount: vi.fn(() => of(undefined)),
  };

  const mockRouter = { navigate: vi.fn() };

  await TestBed.configureTestingModule({
    imports: [AccountsPanelComponent],
    providers: [
      { provide: Store, useValue: mockStore },
      { provide: AccountSessionService, useValue: mockSession },
      { provide: AccountsService, useValue: mockAccountsApi },
      { provide: Router, useValue: mockRouter },
    ],
  }).compileComponents();

  const fixture = TestBed.createComponent(AccountsPanelComponent);
  fixture.detectChanges();

  return {
    fixture,
    el: fixture.nativeElement as HTMLElement,
    component: fixture.componentInstance,
    mockSession,
    mockAccountsApi,
    mockRouter,
    mockStore,
  };
}

describe('AccountsPanelComponent', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('marks the active account card with an active indicator', async () => {
    const { el } = await setup({
      accounts: [account('acc-1'), account('acc-2')],
      activeAccountId: 'acc-1',
    });
    const cards = el.querySelectorAll('.account-card');
    expect(cards[0].classList.contains('account-card--active')).toBe(true);
    expect(cards[1].classList.contains('account-card--active')).toBe(false);
  });

  it('shows a "Log out" button only on the active account card', async () => {
    const { el } = await setup({
      accounts: [account('acc-1'), account('acc-2')],
      activeAccountId: 'acc-1',
    });
    const cards = el.querySelectorAll('.account-card');
    expect(cards[0].querySelector('.logout-btn')).not.toBeNull();
    expect(cards[1].querySelector('.logout-btn')).toBeNull();
  });

  it('shows a "Delete account" button on every account card', async () => {
    const { el } = await setup({
      accounts: [account('acc-1'), account('acc-2')],
      activeAccountId: 'acc-1',
    });
    const cards = el.querySelectorAll('.account-card');
    expect(cards[0].querySelector('.delete-btn')).not.toBeNull();
    expect(cards[1].querySelector('.delete-btn')).not.toBeNull();
  });

  it('never renders a "switch account" button', async () => {
    const { el } = await setup({
      accounts: [account('acc-1'), account('acc-2')],
      activeAccountId: 'acc-1',
    });
    expect(el.querySelector('.switch-btn')).toBeNull();
    expect(el.textContent?.toLowerCase()).not.toContain('switch');
  });

  it('logout invalidates session, clears session state, and redirects to /login', async () => {
    const { component, mockSession, mockAccountsApi, mockRouter } = await setup({
      accounts: [account('acc-1')],
      activeAccountId: 'acc-1',
    });

    component.logout(account('acc-1'));

    expect(mockAccountsApi.logout).toHaveBeenCalledWith('acc-1');
    expect(mockSession.clearSession).toHaveBeenCalled();
    expect(mockRouter.navigate).toHaveBeenCalledWith(['/login']);
  });

  it('deleting the active account logs out then redirects to /login', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const { component, mockSession, mockAccountsApi, mockRouter } = await setup({
      accounts: [account('acc-1')],
      activeAccountId: 'acc-1',
    });

    component.deleteAccount(account('acc-1'));

    expect(mockAccountsApi.deleteAccount).toHaveBeenCalledWith('acc-1');
    expect(mockSession.clearSession).toHaveBeenCalled();
    expect(mockRouter.navigate).toHaveBeenCalledWith(['/login']);
  });

  it('deleting a non-active account removes record without touching the session', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const { component, mockSession, mockAccountsApi, mockRouter, mockStore } = await setup({
      accounts: [account('acc-1'), account('acc-2')],
      activeAccountId: 'acc-1',
    });

    component.deleteAccount(account('acc-2'));

    expect(mockAccountsApi.deleteAccount).toHaveBeenCalledWith('acc-2');
    expect(mockSession.clearSession).not.toHaveBeenCalled();
    expect(mockRouter.navigate).not.toHaveBeenCalled();
    expect(mockStore.dispatch).toHaveBeenCalledWith(expect.any(AccountRemoved));
  });

  it('does not delete when the user cancels the confirm dialog', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(false);
    const { component, mockAccountsApi } = await setup({
      accounts: [account('acc-1')],
      activeAccountId: 'acc-1',
    });

    component.deleteAccount(account('acc-1'));

    expect(mockAccountsApi.deleteAccount).not.toHaveBeenCalled();
  });
});
