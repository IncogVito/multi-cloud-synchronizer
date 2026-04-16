# Frontend Improvement Plan

## 1. NGXS State Management

### Setup
- Install `@ngxs/store`, `@ngxs/devtools-plugin`
- Bootstrap in `app.config.ts`

### Subscription safety
- Every component subscribing to store selects or observables **must** use `takeUntil(this.destroy$)`
- Pattern: private `destroy$ = new Subject<void>()` + `ngOnDestroy() { this.destroy$.next(); this.destroy$.complete(); }`
- Applies to: `@Select()` pipes, polling intervals, SSE streams, any `subscribe()` call
- Polling started in store effects must be tied to component lifecycle via dispatch of `StopPolling` from `ngOnDestroy`

### DevicesState
- State: `DeviceStatusResponse[]` + polling flags
- Actions: `LoadDevices`, `UpdateDevice`, `StartPolling`, `StopPolling`
- Auto-poll **inactive** devices (iPhone, iCloud) every 5s when status != `CONNECTED`
- Drive: poll every 10s always
- Replace manual refresh in `DeviceStatusPanelComponent` + `SyncSectionComponent` with store dispatch

**Dashboard wiring:**
- `DeviceStatusPanelComponent` → `@Select(DevicesState.devices)` replaces local `devices` signal; remove manual `loadStatuses()` calls
- `SyncSectionComponent` → `@Select(DevicesState.devices)` drives device availability check before allowing sync start; remove `refresh()` on init
- `DeviceStatusPanelComponent.recheckDevice()` → dispatch `UpdateDevice` action instead of raw SSE fetch; store absorbs SSE result
- `AppComponent` (or dashboard root) → dispatch `StartPolling` on init, `StopPolling` on destroy

### AccountsState
- State: `AccountResponse[]`
- Actions: `LoadAccounts`, `AddAccount`, `RemoveAccount`
- Shared across dashboard — replaces redundant fetches per component

**Dashboard wiring:**
- `AccountsPanelComponent` → `@Select(AccountsState.accounts)` replaces local `accounts` signal; remove `loadAccounts()` called by parent
- `SyncSectionComponent` → `@Select(AccountsState.accounts)` for account picker; dispatch `LoadAccounts` instead of direct service call
- Account add/delete → dispatch `AddAccount`/`RemoveAccount` → store refreshes → all subscribers update automatically

### PhotosState
- State: photos by month key (`"YYYY-MM"`), months summary, active month
- Actions: `LoadMonthsSummary`, `LoadMonth`, `SetActiveMonth`
- Cache loaded months (no re-fetch on revisit)

**Dashboard wiring:**
- `StatsComponent` (if present on dashboard) → subscribe to `PhotosState.monthsSummary` for total/per-month counts instead of separate stats API call
- Sync completion SSE event → dispatch `LoadMonthsSummary` to refresh counts reactively
- `DiskIndexingService` completion event → dispatch `LoadMonthsSummary` + `LoadMonth` for current active month

---

## 2. Month-Based Photo Browsing

### Backend changes

**New endpoint — months summary:**
```
GET /api/photos/months-summary?storageDeviceId=...
→ [{ year: 2024, month: 3, count: 142 }, ...]  sorted desc
```
- SQL: `SELECT strftime('%Y', created_date), strftime('%m', ...), COUNT(*) FROM photos GROUP BY ...`
- Filter by `storage_device_id` if provided

**Extend listPhotos:**
```
GET /api/photos?year=2024&month=3&...existing params
```
- Add optional `year` + `month` query params to existing `PhotoController.listPhotos()`

### Frontend changes

**TOC sidebar / header nav:**
- List of months with counts (from `LoadMonthsSummary`)
- Sticky, scrollable if many months
- Active month highlighted

**Main photo area:**
- Show photos for active month only (from `PhotosState`)
- On month click → dispatch `SetActiveMonth` + `LoadMonth` if not cached
- At scroll bottom → auto-advance to next (older) month
- Pagination within month still uses page/size for large months

---

## 3. Thumbnail Optimization

### Problem
FE fires HTTP requests for thumbnails even when `thumbnailPath` is `null` (thumbnail doesn't exist) → flood of 404s.

### Solution (FE only — `thumbnailPath` already in `PhotoResponse`)
- In `PhotoTimelineComponent`: before queuing thumbnail fetch, skip if `photo.thumbnailPath == null`
- Show grey placeholder for missing thumbnails immediately, no request sent
- Eliminates wasted requests + 404 noise in network tab

### If thumbnailPath proves unreliable (fallback)
- New BE endpoint: `GET /api/photos/thumbnail-existence?ids=id1,id2,...` → `Map<String, Boolean>`
- Batch-check on month load, store results in `PhotosState`

---

## Execution Order

| # | Task | Scope |
|---|------|-------|
| 1 | Thumbnail skip (check `thumbnailPath != null`) | FE only |
| 2 | `GET /api/photos/months-summary` endpoint | BE |
| 3 | Add `year`/`month` filter to `listPhotos` | BE |
| 4 | Regenerate `openapi.yml` + Orval client | BE→FE |
| 5 | NGXS install + `DevicesState` + polling | FE |
| 6 | `AccountsState` | FE |
| 7 | `PhotosState` + months TOC UI | FE |
| 8 | Wire SSE events (sync, disk indexing) to store updates | FE |
