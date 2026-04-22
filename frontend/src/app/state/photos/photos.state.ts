import { Injectable } from '@angular/core';
import { Action, Selector, State, StateContext } from '@ngxs/store';
import { tap, catchError, EMPTY } from 'rxjs';
import { PhotosService } from '../../core/api/generated/photos/photos.service';
import { PhotoResponse } from '../../core/api/generated/model/photoResponse';
import { MonthSummaryResponse } from '../../core/api/generated/model/monthSummaryResponse';
import {
  ClearDeletedPhotos,
  ClearPhotosPendingDeletion,
  LoadMonthsSummary,
  LoadMorePhotos,
  LoadPhotos,
  MarkPhotosDeleted,
  MarkPhotosPendingDeletion,
  ResetPhotos,
  SetActiveMonth,
  SetColumnsPerRow,
  SetShowDetails,
} from './photos.actions';

const DEFAULT_PAGE_SIZE = 2000;

export interface PhotosStateModel {
  /** Flat list of loaded photos, accumulated across pages. */
  photos: PhotoResponse[];
  /** Summary per calendar month, used for the TOC sidebar. */
  monthsSummary: MonthSummaryResponse[];
  /** Active year-month filter "YYYY-MM", or null for all months. */
  activeMonth: string | null;
  /** Current storage device context for which photos are loaded. */
  activeStorageDeviceId: string | null;
  loading: boolean;
  loadingMore: boolean;
  hasMore: boolean;
  pendingDeletionIds: string[];
  deletedIds: string[];
  currentPage: number;
  error: string | null;
  showDetails: boolean;
  columnsPerRow: number;
}

@State<PhotosStateModel>({
  name: 'photos',
  defaults: {
    photos: [],
    monthsSummary: [],
    activeMonth: null,
    activeStorageDeviceId: null,
    loading: false,
    loadingMore: false,
    hasMore: false,
    currentPage: 0,
    error: null,
    showDetails: false,
    columnsPerRow: 7,
    pendingDeletionIds: [],
    deletedIds: [],
  },
})
@Injectable()
export class PhotosState {
  constructor(private readonly photosService: PhotosService) {}

  @Selector()
  static photos(state: PhotosStateModel): PhotoResponse[] {
    return state.photos;
  }

  @Selector()
  static monthsSummary(state: PhotosStateModel): MonthSummaryResponse[] {
    return state.monthsSummary;
  }

  @Selector()
  static activeMonth(state: PhotosStateModel): string | null {
    return state.activeMonth;
  }

  @Selector()
  static loading(state: PhotosStateModel): boolean {
    return state.loading;
  }

  @Selector()
  static loadingMore(state: PhotosStateModel): boolean {
    return state.loadingMore;
  }

  @Selector()
  static hasMore(state: PhotosStateModel): boolean {
    return state.hasMore;
  }

  @Selector()
  static error(state: PhotosStateModel): string | null {
    return state.error;
  }

  @Selector()
  static showDetails(state: PhotosStateModel): boolean {
    return state.showDetails;
  }

  @Selector()
  static columnsPerRow(state: PhotosStateModel): number {
    return state.columnsPerRow;
  }

  @Selector()
  static pendingDeletionIds(state: PhotosStateModel): string[] {
    return state.pendingDeletionIds;
  }

  @Selector()
  static deletedIds(state: PhotosStateModel): string[] {
    return state.deletedIds;
  }

  /**
   * TODO: Test that LoadMonthsSummary updates correctly after a sync completes.
   * TODO: Test that months with zero synced photos are not returned.
   */
  @Action(LoadMonthsSummary)
  loadMonthsSummary(ctx: StateContext<PhotosStateModel>, action: LoadMonthsSummary) {
    return this.photosService.getMonthsSummary({
      storageDeviceId: action.storageDeviceId,
      accountId: action.accountId ?? '',
    }).pipe(
      tap(summary => ctx.patchState({ monthsSummary: summary })),
      catchError(err => {
        ctx.patchState({ error: err?.message ?? 'Failed to load months summary' });
        return EMPTY;
      }),
    );
  }

  /**
   * Resets photo list and loads the first page for the given device/month.
   *
   * TODO: Test that calling LoadPhotos while a previous load is in-flight does not
   * produce duplicate entries (NGXS cancels pending observables on re-dispatch).
   * TODO: Test year filter returns only photos from that calendar year.
   * TODO: Test yearMonth filter returns only photos from that specific month.
   */
  @Action(LoadPhotos)
  loadPhotos(ctx: StateContext<PhotosStateModel>, action: LoadPhotos) {
    ctx.patchState({
      loading: true,
      photos: [],
      currentPage: 0,
      hasMore: false,
      error: null,
      activeStorageDeviceId: action.storageDeviceId,
    });

    return this.photosService.listPhotos({
      storageDeviceId: action.storageDeviceId,
      synced: 'true',
      accountId: '',
      yearMonth: action.yearMonth ?? '',
      year: action.year ?? '',
      page: 0,
      size: DEFAULT_PAGE_SIZE,
    }).pipe(
      tap(response => {
        const photos = response.photos ?? [];
        ctx.patchState({
          photos,
          loading: false,
          hasMore: photos.length < (response.total ?? 0),
        });
      }),
      catchError(err => {
        ctx.patchState({ loading: false, error: err?.message ?? 'Failed to load photos' });
        return EMPTY;
      }),
    );
  }

  /**
   * Appends the next page of photos to the existing list.
   *
   * TODO: Test that LoadMorePhotos is a no-op when hasMore is false.
   * TODO: Test that concurrent LoadMorePhotos dispatches do not produce duplicates.
   */
  @Action(LoadMorePhotos)
  loadMorePhotos(ctx: StateContext<PhotosStateModel>) {
    const state = ctx.getState();
    if (!state.hasMore || state.loadingMore || !state.activeStorageDeviceId) return;

    const nextPage = state.currentPage + 1;
    ctx.patchState({ loadingMore: true, currentPage: nextPage });

    return this.photosService.listPhotos({
      storageDeviceId: state.activeStorageDeviceId,
      synced: 'true',
      accountId: '',
      yearMonth: state.activeMonth ?? '',
      year: '',
      page: nextPage,
      size: DEFAULT_PAGE_SIZE,
    }).pipe(
      tap(response => {
        const newPhotos = response.photos ?? [];
        const allPhotos = [...state.photos, ...newPhotos];
        ctx.patchState({
          photos: allPhotos,
          loadingMore: false,
          hasMore: allPhotos.length < (response.total ?? 0),
        });
      }),
      catchError(() => {
        ctx.patchState({ loadingMore: false });
        return EMPTY;
      }),
    );
  }

  /**
   * Sets the active month filter and reloads photos for the new month.
   *
   * TODO: Test that switching months clears the previous photo list immediately
   * (no flash of stale photos from the prior month).
   */
  @Action(SetActiveMonth)
  setActiveMonth(ctx: StateContext<PhotosStateModel>, action: SetActiveMonth) {
    const state = ctx.getState();
    ctx.patchState({ activeMonth: action.yearMonth });

    if (state.activeStorageDeviceId) {
      return ctx.dispatch(new LoadPhotos(
        state.activeStorageDeviceId,
        action.yearMonth ?? undefined,
      ));
    }
    return;
  }

  @Action(SetShowDetails)
  setShowDetails(ctx: StateContext<PhotosStateModel>, action: SetShowDetails): void {
    ctx.patchState({ showDetails: action.showDetails });
  }

  @Action(SetColumnsPerRow)
  setColumnsPerRow(ctx: StateContext<PhotosStateModel>, action: SetColumnsPerRow): void {
    ctx.patchState({ columnsPerRow: action.columns });
  }

  @Action(ResetPhotos)
  resetPhotos(ctx: StateContext<PhotosStateModel>): void {
    ctx.patchState({
      photos: [],
      currentPage: 0,
      hasMore: false,
      loading: false,
      loadingMore: false,
      error: null,
    });
  }

  @Action(MarkPhotosPendingDeletion)
  markPhotosPendingDeletion(ctx: StateContext<PhotosStateModel>, action: MarkPhotosPendingDeletion): void {
    const existing = new Set(ctx.getState().pendingDeletionIds);
    action.ids.forEach(id => existing.add(id));
    ctx.patchState({ pendingDeletionIds: [...existing] });
  }

  @Action(MarkPhotosDeleted)
  markPhotosDeleted(ctx: StateContext<PhotosStateModel>, action: MarkPhotosDeleted): void {
    const pending = new Set(ctx.getState().pendingDeletionIds);
    action.ids.forEach(id => pending.delete(id));
    const existingDeleted = new Set(ctx.getState().deletedIds);
    action.ids.forEach(id => existingDeleted.add(id));
    ctx.patchState({
      pendingDeletionIds: [...pending],
      deletedIds: [...existingDeleted],
    });
  }

  @Action(ClearPhotosPendingDeletion)
  clearPhotosPendingDeletion(ctx: StateContext<PhotosStateModel>, action: ClearPhotosPendingDeletion): void {
    const toRemove = new Set(action.ids);
    ctx.patchState({
      pendingDeletionIds: ctx.getState().pendingDeletionIds.filter(id => !toRemove.has(id)),
    });
  }

  @Action(ClearDeletedPhotos)
  clearDeletedPhotos(ctx: StateContext<PhotosStateModel>): void {
    const toRemove = new Set(ctx.getState().deletedIds);
    ctx.patchState({
      photos: ctx.getState().photos.filter(p => !toRemove.has(p.id)),
      deletedIds: [],
    });
  }
}
