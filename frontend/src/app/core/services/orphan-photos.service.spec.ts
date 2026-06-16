import '@angular/compiler';
import { vi, describe, it, expect, beforeEach, afterEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { OrphanPhotosService } from './orphan-photos.service';
import { AuthService } from './auth.service';

describe('OrphanPhotosService', () => {
  let service: OrphanPhotosService;
  let auth: { getCredentials: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    auth = { getCredentials: vi.fn().mockReturnValue({ encoded: 'dXNlcjpwYXNz' }) };

    TestBed.configureTestingModule({
      providers: [
        OrphanPhotosService,
        { provide: AuthService, useValue: auth },
      ],
    });
    service = TestBed.inject(OrphanPhotosService);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('refreshCount() hits the account-scoped count endpoint and stores the count', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ count: 5 }), { status: 200 }),
    );
    vi.stubGlobal('fetch', fetchMock);

    await service.refreshCount('acc-1');

    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('/api/sync/acc-1/orphan-photos/count');
    expect(init.headers['Authorization']).toBe('Basic dXNlcjpwYXNz');
    expect(service.orphanCount()).toBe(5);
  });

  it('refreshCount() leaves count unchanged on non-ok response', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response('nope', { status: 500 }));
    vi.stubGlobal('fetch', fetchMock);

    await service.refreshCount('acc-1');

    expect(service.orphanCount()).toBe(0);
  });

  it('startJob() POSTs to the assign endpoint and opens the SSE stream', async () => {
    const fetchMock = vi.fn()
      // 1) POST assign → returns jobId
      .mockResolvedValueOnce(new Response(JSON.stringify({ jobId: 'job-1' }), { status: 200 }))
      // 2) GET SSE progress → never resolves (kept open)
      .mockReturnValueOnce(new Promise<Response>(() => { /* never resolves */ }));
    vi.stubGlobal('fetch', fetchMock);

    await service.startJob('acc-1');

    expect(fetchMock.mock.calls[0][0]).toBe('/api/sync/acc-1/orphan-photos/assign');
    expect(fetchMock.mock.calls[0][1].method).toBe('POST');
    expect(fetchMock.mock.calls[1][0]).toBe('/api/sync/orphan-photos/job-1/progress');
    expect(fetchMock.mock.calls[1][1].headers['Accept']).toBe('text/event-stream');
    expect(service.running()).toBe(true);
  });

  it('startJob() stops running when the assign POST fails', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response('err', { status: 500 }));
    vi.stubGlobal('fetch', fetchMock);

    await service.startJob('acc-1');

    expect(service.running()).toBe(false);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('parses SSE progress, marks done, resets count and clears running', async () => {
    const sse =
      'data: {"processed":1,"total":2,"assigned":1,"done":false}\n' +
      'data: {"processed":2,"total":2,"assigned":2,"done":true}\n';
    const stream = new Response(sse, {
      status: 200,
      headers: { 'Content-Type': 'text/event-stream' },
    });

    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ jobId: 'job-2' }), { status: 200 }))
      .mockResolvedValueOnce(stream);
    vi.stubGlobal('fetch', fetchMock);

    service.orphanCount.set(2);
    await service.startJob('acc-1');
    // allow the streaming microtasks to settle
    await new Promise(r => setTimeout(r, 0));

    expect(service.progress()?.assigned).toBe(2);
    expect(service.progress()?.done).toBe(true);
    expect(service.running()).toBe(false);
    expect(service.orphanCount()).toBe(0);
  });
});
