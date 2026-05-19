import '@angular/compiler';
import { vi, describe, it, expect, beforeEach, afterEach } from 'vitest';
import { Subject, of, throwError } from 'rxjs';
import { ThumbnailSpriteService, buildPages } from './thumbnail-sprite.service';

// ─── helpers ────────────────────────────────────────────────────────────────

const makeManifest = (photoIds: string[], spriteId = 'sprite-1') => ({
  spriteId,
  spriteWidth: 300,
  spriteHeight: photoIds.length * 300,
  slots: Object.fromEntries(photoIds.map((id, i) => [id, { x: 0, y: i * 300, w: 300, h: 300 }])),
});

const emptyManifest = { spriteId: 'empty', spriteWidth: 0, spriteHeight: 0, slots: {} };

// ─── buildPages (pure function) ─────────────────────────────────────────────

describe('buildPages', () => {
  it('returns empty array for empty input', () => {
    expect(buildPages([], 10, 4)).toEqual([]);
  });

  it('returns single page when items fit in one page', () => {
    expect(buildPages([1, 2, 3], 10, 4)).toEqual([[1, 2, 3]]);
  });

  it('splits into multiple pages of pageSize', () => {
    const result = buildPages([1, 2, 3, 4, 5], 2, 4);
    expect(result).toEqual([[1, 2], [3, 4], [5]]);
  });

  it('caps at maxPages pages', () => {
    const items = Array.from({ length: 100 }, (_, i) => i);
    const result = buildPages(items, 10, 3);
    expect(result).toHaveLength(3);
    expect(result[2]).toEqual(items.slice(20, 30));
  });

  it('returns empty array when maxPages is 0', () => {
    expect(buildPages([1, 2, 3], 10, 0)).toEqual([]);
  });

  it('each page has at most pageSize items', () => {
    const result = buildPages(Array.from({ length: 25 }, (_, i) => i), 8, 4);
    result.forEach(page => expect(page.length).toBeLessThanOrEqual(8));
  });
});

// ─── ThumbnailSpriteService ──────────────────────────────────────────────────

describe('ThumbnailSpriteService', () => {
  let service: ThumbnailSpriteService;
  let mockPost: ReturnType<typeof vi.fn>;
  let mockGet: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    vi.useFakeTimers();
    vi.stubGlobal('URL', {
      createObjectURL: vi.fn(() => 'blob:mock-url'),
      revokeObjectURL: vi.fn(),
    });

    mockPost = vi.fn();
    mockGet = vi.fn();
    service = new ThumbnailSpriteService({ post: mockPost, get: mockGet } as any);
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  // ── request() ──────────────────────────────────────────────────────────────

  describe('request()', () => {
    it('schedules a flush after debounce delay', () => {
      mockPost.mockReturnValue(of(emptyManifest));

      service.request('p1');
      expect(mockPost).not.toHaveBeenCalled();

      vi.advanceTimersByTime(150);
      expect(mockPost).toHaveBeenCalledOnce();
    });

    it('is a no-op for a photo already in slots', () => {
      const manifest = makeManifest(['p1']);
      mockPost.mockReturnValue(of(manifest));
      mockGet.mockReturnValue(of(new Blob()));

      service.request('p1');
      vi.advanceTimersByTime(150);

      mockPost.mockClear();
      service.request('p1');
      vi.advanceTimersByTime(150);

      expect(mockPost).not.toHaveBeenCalled();
    });

    it('is a no-op for a photo already pending', () => {
      const subject = new Subject();
      mockPost.mockReturnValue(subject);

      service.request('p1');
      vi.advanceTimersByTime(150);

      // p1 is now in-flight (not pending, not in slots) — but if we add it again
      // before flush clears it, the pending guard should stop it
      // Here we test adding while still in pending set (before flush fires)
      service.reset();
      const mockPost2 = vi.fn().mockReturnValue(of(emptyManifest));
      service = new ThumbnailSpriteService({ post: mockPost2, get: mockGet } as any);

      service.request('p2');
      service.request('p2'); // duplicate before flush
      vi.advanceTimersByTime(150);

      const calledIds = mockPost2.mock.calls[0][1].photoIds as string[];
      expect(calledIds.filter(id => id === 'p2')).toHaveLength(1);
    });
  });

  // ── flush batching ─────────────────────────────────────────────────────────

  describe('flush()', () => {
    it('batches multiple requests into a single POST', () => {
      mockPost.mockReturnValue(of(emptyManifest));

      service.request('p1');
      service.request('p2');
      service.request('p3');
      vi.advanceTimersByTime(150);

      expect(mockPost).toHaveBeenCalledOnce();
      expect(mockPost.mock.calls[0][1].photoIds).toEqual(['p1', 'p2', 'p3']);
    });

    it('debounces rapid requests — only one flush fires', () => {
      mockPost.mockReturnValue(of(emptyManifest));

      service.request('p1');
      vi.advanceTimersByTime(50);
      service.request('p2');
      vi.advanceTimersByTime(150);

      expect(mockPost).toHaveBeenCalledOnce();
    });

    it('downloads the sprite blob and registers slots', () => {
      const manifest = makeManifest(['p1']);
      mockPost.mockReturnValue(of(manifest));
      mockGet.mockReturnValue(of(new Blob()));

      service.request('p1');
      vi.advanceTimersByTime(150);

      expect(service.slots().has('p1')).toBe(true);
      expect(service.slots().get('p1')?.spriteUrl).toBe('blob:mock-url');
    });

    it('does not update slots for empty manifest', () => {
      mockPost.mockReturnValue(of(emptyManifest));

      service.request('p1');
      vi.advanceTimersByTime(150);

      expect(service.slots().has('p1')).toBe(false);
    });

    it('re-schedules flush when there are remaining pending items', () => {
      mockPost
        .mockReturnValueOnce(of(emptyManifest))
        .mockReturnValueOnce(of(emptyManifest));

      // Fill >500 to force two flushes
      for (let i = 0; i < 502; i++) {
        (service as any).pending.add(`p${i}`);
      }
      vi.advanceTimersByTime(0); // flush is already scheduled via request() — trigger manually
      (service as any).flush();
      expect(mockPost).toHaveBeenCalledOnce();

      vi.advanceTimersByTime(150);
      expect(mockPost).toHaveBeenCalledTimes(2);
    });
  });

  // ── prefetchPages() ────────────────────────────────────────────────────────

  describe('prefetchPages()', () => {
    it('fetches first page immediately then chains subsequent pages', () => {
      const page1 = new Subject<any>();
      const page2 = new Subject<any>();
      mockPost.mockReturnValueOnce(page1).mockReturnValueOnce(page2);
      mockGet.mockReturnValue(of(new Blob()));

      service.prefetchPages(['p1', 'p2', 'p3', 'p4'], 2, 2);

      expect(mockPost).toHaveBeenCalledOnce();
      expect(mockPost.mock.calls[0][1].photoIds).toEqual(['p1', 'p2']);

      page1.next(makeManifest(['p1', 'p2']));
      page1.complete();

      expect(mockPost).toHaveBeenCalledTimes(2);
      expect(mockPost.mock.calls[1][1].photoIds).toEqual(['p3', 'p4']);
    });

    it('cancels in-flight prefetch when called again', () => {
      const slowPage = new Subject<any>();
      mockPost.mockReturnValue(slowPage);

      service.prefetchPages(['p1', 'p2'], 2, 1);
      expect(mockPost).toHaveBeenCalledOnce();

      // Second call cancels the first — new IDs
      mockPost.mockReturnValue(of(emptyManifest));
      service.prefetchPages(['p3', 'p4'], 2, 1);

      // Complete slow page after cancellation — should have no effect
      slowPage.next(makeManifest(['p1', 'p2']));
      slowPage.complete();

      expect(service.slots().has('p1')).toBe(false);
      expect(mockPost).toHaveBeenCalledTimes(2);
    });

    it('skips photo IDs already in slots', () => {
      const manifest1 = makeManifest(['p1']);
      mockPost.mockReturnValueOnce(of(manifest1)).mockReturnValue(of(emptyManifest));
      mockGet.mockReturnValue(of(new Blob()));

      // Load p1 via request()
      service.request('p1');
      vi.advanceTimersByTime(150);
      expect(service.slots().has('p1')).toBe(true);

      mockPost.mockClear();
      service.prefetchPages(['p1', 'p2'], 2, 1);

      const calledIds = mockPost.mock.calls[0][1].photoIds as string[];
      expect(calledIds).not.toContain('p1');
      expect(calledIds).toContain('p2');
    });

    it('does nothing for an empty photoIds array', () => {
      service.prefetchPages([], 10, 4);
      expect(mockPost).not.toHaveBeenCalled();
    });

    it('respects maxPages cap', () => {
      mockPost.mockReturnValue(of(emptyManifest));
      const ids = Array.from({ length: 100 }, (_, i) => `p${i}`);

      service.prefetchPages(ids, 10, 2);

      // 2 pages of 10 = 20 IDs max → 2 POST calls
      vi.advanceTimersByTime(0);
      expect(mockPost).toHaveBeenCalledTimes(2);
    });

    it('registers slots for all photos across pages', () => {
      mockPost
        .mockReturnValueOnce(of(makeManifest(['p1', 'p2'])))
        .mockReturnValueOnce(of(makeManifest(['p3', 'p4'])));
      mockGet.mockReturnValue(of(new Blob()));

      service.prefetchPages(['p1', 'p2', 'p3', 'p4'], 2, 2);

      expect(service.slots().has('p1')).toBe(true);
      expect(service.slots().has('p2')).toBe(true);
      expect(service.slots().has('p3')).toBe(true);
      expect(service.slots().has('p4')).toBe(true);
    });
  });

  // ── registerSprite (via fetchBatch side-effects) ───────────────────────────

  describe('registerSprite (via prefetchPages)', () => {
    it('computes col/row from slot x/y divided by 300', () => {
      const manifest = {
        spriteId: 'sprite-1',
        spriteWidth: 600,
        spriteHeight: 900,
        slots: { p1: { x: 300, y: 600, w: 300, h: 300 } },
      };
      mockPost.mockReturnValue(of(manifest));
      mockGet.mockReturnValue(of(new Blob()));

      service.prefetchPages(['p1'], 1, 1);

      const slot = service.slots().get('p1')!;
      expect(slot.col).toBe(1);   // 300/300
      expect(slot.row).toBe(2);   // 600/300
      expect(slot.cols).toBe(2);  // 600/300
      expect(slot.rows).toBe(3);  // 900/300
    });

    it('does not register slots when sprite is empty', () => {
      mockPost.mockReturnValue(of(emptyManifest));

      service.prefetchPages(['p1'], 1, 1);

      expect(service.slots().has('p1')).toBe(false);
    });

    it('creates a blob URL and stores it for later revocation', () => {
      mockPost.mockReturnValue(of(makeManifest(['p1'])));
      mockGet.mockReturnValue(of(new Blob()));

      service.prefetchPages(['p1'], 1, 1);

      expect(URL.createObjectURL).toHaveBeenCalledOnce();
    });
  });

  // ── reset() ───────────────────────────────────────────────────────────────

  describe('reset()', () => {
    it('clears all slots', () => {
      mockPost.mockReturnValue(of(makeManifest(['p1'])));
      mockGet.mockReturnValue(of(new Blob()));
      service.request('p1');
      vi.advanceTimersByTime(150);

      service.reset();
      expect(service.slots().size).toBe(0);
    });

    it('revokes all blob URLs', () => {
      mockPost.mockReturnValue(of(makeManifest(['p1'])));
      mockGet.mockReturnValue(of(new Blob()));
      service.request('p1');
      vi.advanceTimersByTime(150);

      service.reset();
      expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:mock-url');
    });

    it('cancels in-flight prefetch', () => {
      const slowPage = new Subject<any>();
      mockPost.mockReturnValue(slowPage);

      service.prefetchPages(['p1', 'p2', 'p3', 'p4'], 2, 2);
      service.reset();

      // Complete slow page after reset — should not update slots
      slowPage.next(makeManifest(['p1', 'p2']));
      slowPage.complete();

      expect(service.slots().has('p1')).toBe(false);
    });

    it('cancels pending flush timer', () => {
      mockPost.mockReturnValue(of(emptyManifest));
      service.request('p1');
      service.reset();

      vi.advanceTimersByTime(150);
      expect(mockPost).not.toHaveBeenCalled();
    });
  });

  // ── error handling ─────────────────────────────────────────────────────────

  describe('error handling', () => {
    it('prefetchPages continues to next page when a page fetch fails', () => {
      mockPost
        .mockReturnValueOnce(throwError(() => new Error('network')))
        .mockReturnValueOnce(of(makeManifest(['p3', 'p4'])));
      mockGet.mockReturnValue(of(new Blob()));

      service.prefetchPages(['p1', 'p2', 'p3', 'p4'], 2, 2);

      expect(service.slots().has('p3')).toBe(true);
      expect(service.slots().has('p4')).toBe(true);
    });

    it('flush re-schedules on error when pending items remain beyond the 500-batch cap', () => {
      mockPost
        .mockReturnValueOnce(throwError(() => new Error('fail')))
        .mockReturnValueOnce(of(emptyManifest));

      // 502 items → first flush takes 500, leaves 2 in pending
      for (let i = 0; i < 502; i++) {
        (service as any).pending.add(`p${i}`);
      }
      (service as any).flush(); // fires synchronously: takes 500, fails, sees 2 remaining → re-schedules
      expect(mockPost).toHaveBeenCalledTimes(1);

      vi.advanceTimersByTime(150); // re-scheduled flush fires for remaining 2
      expect(mockPost).toHaveBeenCalledTimes(2);
    });
  });
});
