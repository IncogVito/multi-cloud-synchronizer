import '@angular/compiler';
import { vi, describe, it, expect, beforeEach, afterEach } from 'vitest';
import { of, throwError } from 'rxjs';
import { AccountSessionService } from './account-session.service';
import { AccountService } from './account.service';

const STORAGE_KEY = 'active_account_id';

describe('AccountSessionService', () => {
  let storage: Record<string, string>;
  let mockStatus: ReturnType<typeof vi.fn>;
  let mockAccountService: Pick<AccountService, 'getAccountStatus'>;

  beforeEach(() => {
    storage = {};
    mockStatus = vi.fn().mockReturnValue(of({ hasActiveSession: true }));
    mockAccountService = { getAccountStatus: mockStatus };
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(k => storage[k] ?? null);
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation((k, v) => { storage[k] = v; });
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(k => { delete storage[k]; });
  });

  afterEach(() => vi.restoreAllMocks());

  function create(): AccountSessionService {
    return new AccountSessionService(mockAccountService as AccountService);
  }

  it('starts with null when localStorage is empty', () => {
    const service = create();
    expect(service.activeAccountId()).toBeNull();
  });

  it('setActiveAccount persists to localStorage and updates signal', () => {
    const service = create();
    service.setActiveAccount('acc-42');
    expect(storage[STORAGE_KEY]).toBe('acc-42');
    expect(service.activeAccountId()).toBe('acc-42');
  });

  it('clearSession removes localStorage entry and resets signal', () => {
    const service = create();
    service.setActiveAccount('acc-42');
    service.clearSession();
    expect(storage[STORAGE_KEY]).toBeUndefined();
    expect(service.activeAccountId()).toBeNull();
  });

  it('restores from localStorage and keeps id when session is active', () => {
    storage[STORAGE_KEY] = 'acc-stored';
    mockStatus.mockReturnValue(of({ hasActiveSession: true }));
    const service = create();
    expect(service.activeAccountId()).toBe('acc-stored');
  });

  it('clears state on init when stored session is inactive', () => {
    storage[STORAGE_KEY] = 'acc-stored';
    mockStatus.mockReturnValue(of({ hasActiveSession: false }));
    const service = create();
    expect(service.activeAccountId()).toBeNull();
    expect(storage[STORAGE_KEY]).toBeUndefined();
  });

  it('clears state on init when getAccountStatus errors', () => {
    storage[STORAGE_KEY] = 'acc-stored';
    mockStatus.mockReturnValue(throwError(() => new Error('network')));
    const service = create();
    expect(service.activeAccountId()).toBeNull();
  });

  describe('validateSession', () => {
    it('returns false when no active account', () => {
      const service = create();
      let result: boolean | undefined;
      service.validateSession().subscribe(v => (result = v));
      expect(result).toBe(false);
    });

    it('returns true when account has active session', () => {
      const service = create();
      service.setActiveAccount('acc-1');
      mockStatus.mockReturnValue(of({ hasActiveSession: true }));
      let result: boolean | undefined;
      service.validateSession().subscribe(v => (result = v));
      expect(result).toBe(true);
    });

    it('returns false when account has no active session', () => {
      const service = create();
      service.setActiveAccount('acc-1');
      mockStatus.mockReturnValue(of({ hasActiveSession: false }));
      let result: boolean | undefined;
      service.validateSession().subscribe(v => (result = v));
      expect(result).toBe(false);
    });

    it('returns false when API errors', () => {
      const service = create();
      service.setActiveAccount('acc-1');
      mockStatus.mockReturnValue(throwError(() => new Error('fail')));
      let result: boolean | undefined;
      service.validateSession().subscribe(v => (result = v));
      expect(result).toBe(false);
    });
  });
});
