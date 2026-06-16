import '@angular/compiler';
import { vi } from 'vitest';
import { PhotosState, PhotosStateModel } from './photos.state';
import {
  ClearDeletedPhotos,
  ClearPhotosPendingDeletion,
  MarkPhotosDeleted,
  MarkPhotosPendingDeletion,
  SetGroupingMode,
  SetSortMode,
} from './photos.actions';

const defaults: PhotosStateModel = {
  photos: [],
  monthsSummary: [],
  activeMonth: null,
  activeAccountId: null,
  loading: false,
  loadingMore: false,
  hasMore: false,
  currentPage: 0,
  error: null,
  showDetails: false,
  columnsPerRow: 7,
  groupingMode: 'none',
  sortMode: 'date',
  pendingDeletionIds: [],
  deletedIds: [],
};

function makeCtx(stateOverride: Partial<PhotosStateModel> = {}) {
  let state: PhotosStateModel = { ...defaults, ...stateOverride };
  return {
    getState: () => state,
    patchState: vi.fn((patch: Partial<PhotosStateModel>) => {
      state = { ...state, ...patch };
    }),
    setState: vi.fn(),
    dispatch: vi.fn(),
    currentState: () => state,
  };
}

const mockService = { listPhotos: vi.fn(), getMonthsSummary: vi.fn() };
const state = new PhotosState(mockService as any);

describe('PhotosState — MarkPhotosPendingDeletion', () => {
  it('adds ids to pendingDeletionIds', () => {
    const ctx = makeCtx();
    state.markPhotosPendingDeletion(ctx as any, new MarkPhotosPendingDeletion(['p1', 'p2']));

    expect(ctx.currentState().pendingDeletionIds).toContain('p1');
    expect(ctx.currentState().pendingDeletionIds).toContain('p2');
  });

  it('is idempotent — duplicate dispatch does not add duplicates', () => {
    const ctx = makeCtx();
    state.markPhotosPendingDeletion(ctx as any, new MarkPhotosPendingDeletion(['p1']));
    state.markPhotosPendingDeletion(ctx as any, new MarkPhotosPendingDeletion(['p1']));

    expect(ctx.currentState().pendingDeletionIds.filter(id => id === 'p1')).toHaveLength(1);
  });

  it('accumulates ids across multiple calls', () => {
    const ctx = makeCtx();
    state.markPhotosPendingDeletion(ctx as any, new MarkPhotosPendingDeletion(['p1']));
    state.markPhotosPendingDeletion(ctx as any, new MarkPhotosPendingDeletion(['p2', 'p3']));

    const ids = ctx.currentState().pendingDeletionIds;
    expect(ids).toContain('p1');
    expect(ids).toContain('p2');
    expect(ids).toContain('p3');
  });

  it('does not touch deletedIds', () => {
    const ctx = makeCtx({ deletedIds: ['existing'] });
    state.markPhotosPendingDeletion(ctx as any, new MarkPhotosPendingDeletion(['p1']));

    expect(ctx.currentState().deletedIds).toContain('existing');
  });
});

describe('PhotosState — MarkPhotosDeleted', () => {
  it('moves ids from pendingDeletionIds to deletedIds', () => {
    const ctx = makeCtx({ pendingDeletionIds: ['p1', 'p2'] });
    state.markPhotosDeleted(ctx as any, new MarkPhotosDeleted(['p1']));

    const s = ctx.currentState();
    expect(s.pendingDeletionIds).not.toContain('p1');
    expect(s.pendingDeletionIds).toContain('p2');
    expect(s.deletedIds).toContain('p1');
  });

  it('adds ids to deletedIds even if they were not pending', () => {
    const ctx = makeCtx();
    state.markPhotosDeleted(ctx as any, new MarkPhotosDeleted(['p99']));

    expect(ctx.currentState().deletedIds).toContain('p99');
  });

  it('is idempotent — re-marking deleted id does not duplicate', () => {
    const ctx = makeCtx({ pendingDeletionIds: ['p1'] });
    state.markPhotosDeleted(ctx as any, new MarkPhotosDeleted(['p1']));
    state.markPhotosDeleted(ctx as any, new MarkPhotosDeleted(['p1']));

    expect(ctx.currentState().deletedIds.filter(id => id === 'p1')).toHaveLength(1);
  });
});

describe('PhotosState — ClearPhotosPendingDeletion', () => {
  it('removes specified ids from pendingDeletionIds', () => {
    const ctx = makeCtx({ pendingDeletionIds: ['p1', 'p2', 'p3'] });
    state.clearPhotosPendingDeletion(ctx as any, new ClearPhotosPendingDeletion(['p1', 'p3']));

    const ids = ctx.currentState().pendingDeletionIds;
    expect(ids).not.toContain('p1');
    expect(ids).not.toContain('p3');
    expect(ids).toContain('p2');
  });

  it('is a no-op for ids not in pendingDeletionIds', () => {
    const ctx = makeCtx({ pendingDeletionIds: ['p1'] });
    state.clearPhotosPendingDeletion(ctx as any, new ClearPhotosPendingDeletion(['p99']));

    expect(ctx.currentState().pendingDeletionIds).toContain('p1');
  });

  it('does not touch deletedIds', () => {
    const ctx = makeCtx({ pendingDeletionIds: ['p1'], deletedIds: ['p1'] });
    state.clearPhotosPendingDeletion(ctx as any, new ClearPhotosPendingDeletion(['p1']));

    expect(ctx.currentState().deletedIds).toContain('p1');
  });
});

describe('PhotosState — ClearDeletedPhotos', () => {
  it('empties deletedIds', () => {
    const ctx = makeCtx({ deletedIds: ['p1', 'p2'] });
    state.clearDeletedPhotos(ctx as any);

    expect(ctx.currentState().deletedIds).toHaveLength(0);
  });

  it('removes photos with deletedIds from the photos array', () => {
    const photo1 = { id: 'p1' } as any;
    const photo2 = { id: 'p2' } as any;
    const ctx = makeCtx({ photos: [photo1, photo2], deletedIds: ['p1'] });

    state.clearDeletedPhotos(ctx as any);

    expect(ctx.currentState().photos.map(p => p.id)).not.toContain('p1');
    expect(ctx.currentState().photos.map(p => p.id)).toContain('p2');
  });

  it('does not affect pendingDeletionIds', () => {
    const ctx = makeCtx({ deletedIds: ['p1'], pendingDeletionIds: ['p2'] });
    state.clearDeletedPhotos(ctx as any);

    expect(ctx.currentState().pendingDeletionIds).toContain('p2');
  });

  it('is a no-op when no photos are deleted', () => {
    const ctx = makeCtx({ photos: [{ id: 'p1' } as any] });
    state.clearDeletedPhotos(ctx as any);

    expect(ctx.currentState().photos).toHaveLength(1);
    expect(ctx.currentState().deletedIds).toHaveLength(0);
  });
});

describe('PhotosState — SetSortMode', () => {
  it('default sortMode is date', () => {
    const ctx = makeCtx();
    expect(ctx.currentState().sortMode).toBe('date');
  });

  it('switches to size', () => {
    const ctx = makeCtx();
    state.setSortMode(ctx as any, new SetSortMode('size'));
    expect(ctx.currentState().sortMode).toBe('size');
  });

  it('switches back to date', () => {
    const ctx = makeCtx({ sortMode: 'size' });
    state.setSortMode(ctx as any, new SetSortMode('date'));
    expect(ctx.currentState().sortMode).toBe('date');
  });

  it('does not touch groupingMode', () => {
    const ctx = makeCtx({ groupingMode: 'day' });
    state.setSortMode(ctx as any, new SetSortMode('size'));
    expect(ctx.currentState().groupingMode).toBe('day');
  });
});

describe('PhotosState — SetGroupingMode', () => {
  it('default groupingMode is none', () => {
    const ctx = makeCtx();
    expect(ctx.currentState().groupingMode).toBe('none');
  });

  it('switches to day', () => {
    const ctx = makeCtx();
    state.setGroupingMode(ctx as any, new SetGroupingMode('day'));
    expect(ctx.currentState().groupingMode).toBe('day');
  });

  it('switches to hour', () => {
    const ctx = makeCtx();
    state.setGroupingMode(ctx as any, new SetGroupingMode('hour'));
    expect(ctx.currentState().groupingMode).toBe('hour');
  });

  it('does not touch sortMode', () => {
    const ctx = makeCtx({ sortMode: 'size' });
    state.setGroupingMode(ctx as any, new SetGroupingMode('day'));
    expect(ctx.currentState().sortMode).toBe('size');
  });
});
