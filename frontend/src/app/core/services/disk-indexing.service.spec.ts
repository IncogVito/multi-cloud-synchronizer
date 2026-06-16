import '@angular/compiler';
import { vi, describe, it, expect, beforeEach, afterEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { DiskIndexingService } from './disk-indexing.service';
import { DefaultService } from '../api/generated/default/default.service';
import { AuthService } from './auth.service';

describe('DiskIndexingService (account-scoped, issue #8)', () => {
  let service: DiskIndexingService;
  let api: {
    indexAccount: ReturnType<typeof vi.fn>;
    reorganizePreview: ReturnType<typeof vi.fn>;
    reorganize1: ReturnType<typeof vi.fn>;
  };
  let auth: { getCredentials: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    api = {
      indexAccount: vi.fn().mockReturnValue(of({ status: 'STARTED' })),
      reorganizePreview: vi.fn().mockReturnValue(of({ unorganizedCount: 0, samples: [], estimatedFolders: [] })),
      reorganize1: vi.fn().mockReturnValue(of({ moved: 0, errors: 0 })),
    };
    auth = { getCredentials: vi.fn().mockReturnValue({ encoded: 'dXNlcjpwYXNz' }) };

    TestBed.configureTestingModule({
      providers: [
        DiskIndexingService,
        { provide: DefaultService, useValue: api },
        { provide: AuthService, useValue: auth },
      ],
    });
    service = TestBed.inject(DiskIndexingService);
  });

  afterEach(() => vi.restoreAllMocks());

  it('start() calls the account-scoped index endpoint', () => {
    service.start('acc-1').subscribe();
    expect(api.indexAccount).toHaveBeenCalledWith('acc-1');
  });

  it('reorganizePreview() routes to the account-scoped sync endpoint', () => {
    service.reorganizePreview('acc-7').subscribe();
    expect(api.reorganizePreview).toHaveBeenCalledWith('acc-7');
  });

  it('reorganize() routes to the account-scoped sync endpoint', () => {
    service.reorganize('acc-7').subscribe();
    expect(api.reorganize1).toHaveBeenCalledWith('acc-7');
  });

  it('subscribeToEvents() opens the per-account SSE stream with auth header', () => {
    const fetchMock = vi.fn().mockReturnValue(new Promise<Response>(() => { /* never resolves */ }));
    vi.stubGlobal('fetch', fetchMock);

    service.subscribeToEvents('acc-9');

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('/api/sync/acc-9/index/events');
    expect(init.headers['Accept']).toBe('text/event-stream');
    expect(init.headers['Authorization']).toBe('Basic dXNlcjpwYXNz');

    service.closeEvents();
    vi.unstubAllGlobals();
  });

  it('reset() pushes null onto progress$', () => {
    let emitted: unknown = 'unset';
    service.progress$.subscribe(p => (emitted = p));
    service.reset();
    expect(emitted).toBeNull();
  });
});
