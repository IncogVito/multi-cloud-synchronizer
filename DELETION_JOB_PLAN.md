# Plan: Async Batch Deletion with Global Task Indicator

## Problem Summary

1. Delete button tries to remove items even when `existsOnIcloud = false` → backend returns 500
2. Deletes are synchronous and sequential — slow for large batches
3. UI blocks and reloads the whole photo grid after every delete
4. No persistent task indicator across pages

---

## Goals

| # | Goal |
|---|------|
| G1 | Guard: only delete items where the flag matches the target provider |
| G2 | Batch + parallel deletion as a background job |
| G3 | Parallel execution inside icloud-service |
| G4 | Global task indicator at app shell level (bottom-right) |
| G5 | Optimistic UI: photos go orange while job runs; stay orange (marked deleted) on completion; "Clear N removed" button appears above grid to physically remove them |

---

## Architecture Overview

Frontend is **store-centric**: components dispatch NGXS actions, stores call APIs and update state. No business logic in components or singleton services.

```
Frontend                      Backend (Java/Micronaut)         icloud-service (Python)
─────────                     ────────────────────────         ───────────────────────
[BatchActionsBar]
  └─ dispatch StartDeletion   POST /api/photos/deletion-jobs
     Action (guard inside) ──────────────────────────────►  DeletionJob (in-memory)
                                                              ├─ status: RUNNING
[JobsState (NGXS)]                                           └─ runs on virtual thread
  └─ calls API                                                   ├─ chunks photoIds
  └─ opens SSE               GET /api/jobs/{id}/progress         ├─ calls icloud-service
  └─ updates jobs[]    ◄──── (SSE stream, unified)          POST /photos/batch-delete
  └─ dispatch                                             ──►    asyncio.gather()
     MarkPendingDeletion                                          (N concurrent deletes)

[PhotosState (NGXS)]
  └─ pendingIds: Set<string>
  └─ deletedIds: Set<string>   ← stays after job complete
  └─ "Clear removed" action
     removes deletedIds
     from photos[]

[GlobalTaskBarComponent]
  └─ reads JobsState.jobs
  └─ zero component logic

[PhotoGridComponent]
  └─ reads PhotosState
  └─ orange overlay if pendingIds
  └─ strikethrough + orange if deletedIds
  └─ "Clear N removed" button if deletedIds.size > 0
```

---

## Part 1 — icloud-service: Batch Delete Endpoint

Add to `icloud-service/app/routers/photos.py`:

```
POST /photos/batch-delete
Body: { "photo_ids": ["id1", "id2", ...] }
Header: X-Session-ID
Response: { "results": [{"photo_id": "id1", "deleted": true, "error": null}, ...] }
```

Why `POST` not `DELETE`: HTTP DELETE with body is non-standard and some clients/proxies strip it.

### Implementation

```python
class BatchDeleteRequest(BaseModel):
    photo_ids: list[str]

@router.post("/batch-delete")
async def batch_delete_photos(
    request: BatchDeleteRequest,
    x_session_id: str = Header(...)
):
    sem = asyncio.Semaphore(10)

    async def delete_one(photo_id: str) -> dict:
        async with sem:
            try:
                await asyncio.to_thread(photo_service.delete_photo, x_session_id, photo_id)
                return {"photo_id": photo_id, "deleted": True, "error": None}
            except Exception as e:
                return {"photo_id": photo_id, "deleted": False, "error": str(e)}

    results = await asyncio.gather(*[delete_one(pid) for pid in request.photo_ids])
    return {"results": list(results)}
```

**Chunk size:** icloud-service accepts up to 50 IDs per request. Backend sends chunks of 50 (configurable via `deletion.batch-chunk-size` in `application.yml`).

**Parallelism limit:** `asyncio.Semaphore(10)` per request — avoids iCloud rate-limiting.

---

## Part 2 — Backend: DeletionJob Infrastructure

Mirrors `ThumbnailJob` / `ThumbnailJobService` pattern.

### New Files

```
backend/src/main/java/com/cloudsync/
  service/
    DeletionJob.java            # job state + progress tracking
    DeletionJobService.java     # create/run/query/cancel jobs
  model/dto/
    DeletionJobRequest.java     # { accountId, photoIds, provider }
    DeletionProgress.java       # SSE event payload: { jobId, total, deleted, failed, successfulIds, failedIds, status }
  controller/
    DeletionJobController.java  # REST endpoints
    JobsController.java         # NEW: generic unified jobs endpoint
```

### DeletionJob State

```java
public class DeletionJob {
    String jobId;           // UUID
    String accountId;
    Provider provider;      // ICLOUD | IPHONE
    List<String> photoIds;
    int total;
    AtomicInteger deleted;
    AtomicInteger failed;
    JobStatus status;       // QUEUED | RUNNING | COMPLETED | CANCELLED | FAILED
    JobType type;           // DELETION (shared enum with ThumbnailJob)
    Instant createdAt;
    Sinks.Many<DeletionProgress> progressSink; // SSE replay buffer, capacity 5
    List<String> failedIds;
    List<String> successfulIds; // populated as job runs, sent in final SSE event
}
```

### DeletionJobService Logic

```
startJob(accountId, photoIds, provider):
  1. SERVER-SIDE GUARD: query DB, keep only IDs where existsOnIcloud=true (ICLOUD) or existsOnIphone=true (IPHONE)
     - Returns { jobId, accepted: N, skipped: M }
  2. Create DeletionJob, store in ConcurrentHashMap
  3. Start virtual thread: runJob(job)

runJob(job):
  1. Chunk photoIds into groups of batchChunkSize (default 50)
  2. For each chunk sequentially:
     a. Call icloud-service POST /photos/batch-delete
     b. For each result:
        - success: UPDATE photo SET exists_on_icloud=false, increment deleted, add to successfulIds
        - failure: increment failed, add to failedIds
     c. Emit DeletionProgress SSE event (partial progress)
  3. Emit final DeletionProgress with full successfulIds + failedIds, status=COMPLETED
  4. Close sink

Auto-cleanup: @Scheduled(fixedDelay = "2h") removes completed/failed jobs older than 2h
```

### REST Endpoints

#### DeletionJobController

```
POST   /api/photos/deletion-jobs              # start → { jobId, accepted, skipped }
GET    /api/photos/deletion-jobs/{id}         # status snapshot
GET    /api/photos/deletion-jobs/{id}/progress  # SSE stream
DELETE /api/photos/deletion-jobs/{id}         # cancel
```

#### JobsController (NEW — generic unified view)

```
GET /api/jobs          # all active+recent jobs (deletion + thumbnail), for GlobalTaskBar polling
GET /api/jobs/{id}/progress  # SSE for any job type, routes to correct service by jobId prefix or lookup
```

`GET /api/jobs` response:
```json
{
  "jobs": [
    { "jobId": "...", "type": "DELETION", "status": "RUNNING", "total": 100, "done": 64, "failed": 2, "label": "Deleting photos (iCloud)" },
    { "jobId": "...", "type": "THUMBNAIL", "status": "RUNNING", "total": 50, "done": 32, "failed": 0, "label": "Generating thumbnails" }
  ]
}
```

`JobsController` injects both `DeletionJobService` and `ThumbnailJobService`, merges their active jobs. For SSE, routes by jobId to correct service.

**This replaces frontend polling individual job endpoints** — `JobsState` subscribes to SSE per-job after learning jobId, but polls `GET /api/jobs` on init to re-attach to jobs that survived navigation or page refresh.

### DB: No New Table

`deleted` / `deleted_date` columns already exist (V10/V11). Update `exists_on_icloud = false` as each photo is deleted.

### Old Endpoint Deprecation

`DELETE /api/photos/icloud` → returns `410 Gone` with body: `{"message": "Use POST /api/photos/deletion-jobs instead"}`.

---

## Part 3 — Frontend State Architecture

### JobsState (NGXS — NEW, generic)

```typescript
// state/jobs/jobs.state.ts

export interface Job {
  jobId: string;
  type: 'DELETION' | 'THUMBNAIL' | 'SYNC';
  status: 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'CANCELLED' | 'FAILED';
  label: string;
  total: number;
  done: number;
  failed: number;
  affectedPhotoIds?: string[];   // for DELETION jobs
}

export interface JobsStateModel {
  jobs: Job[];
}

// Actions
export class StartDeletionJob {
  static readonly type = '[Jobs] Start Deletion Job';
  constructor(public payload: { accountId: string; photoIds: string[]; provider: 'ICLOUD' | 'IPHONE' }) {}
}
export class TrackJob {
  static readonly type = '[Jobs] Track Job';
  constructor(public jobId: string, public type: Job['type'], public label: string, public affectedPhotoIds?: string[]) {}
}
export class UpdateJobProgress {
  static readonly type = '[Jobs] Update Progress';
  constructor(public jobId: string, public patch: Partial<Job>) {}
}
export class RemoveJob {
  static readonly type = '[Jobs] Remove';
  constructor(public jobId: string) {}
}
export class LoadActiveJobs {
  static readonly type = '[Jobs] Load Active';  // called on app init / navigation
}
```

`@Action(StartDeletionJob)`:
1. Guard: filter `photoIds` by `existsOnIcloud` (frontend fast-path)
2. Call `POST /api/photos/deletion-jobs`
3. Dispatch `TrackJob` with `jobId` + `affectedPhotoIds`
4. Dispatch `MarkPhotosPendingDeletion` to `PhotosState`

`@Action(TrackJob)`:
1. Add job to `jobs[]`
2. Open SSE to `GET /api/photos/deletion-jobs/{jobId}/progress`
3. On each SSE event: `dispatch(UpdateJobProgress(...))`
4. On COMPLETED: dispatch `MarkPhotosDeleted(successfulIds)` to `PhotosState`; dispatch `RemoveJob` after 5s delay
5. On FAILED/CANCELLED: dispatch `ClearPhotosPendingDeletion(affectedIds)` to `PhotosState`; show toast

`@Action(LoadActiveJobs)`:
1. Call `GET /api/jobs`
2. For each running job: dispatch `TrackJob` to re-attach SSE
3. For each deletion job: dispatch `MarkPhotosPendingDeletion` with known `affectedPhotoIds` (if stored)

### PhotosState Changes (NGXS — existing state extended)

```typescript
export interface PhotosStateModel {
  photos: PhotoResponse[];
  // ... existing fields ...
  pendingDeletionIds: string[];  // NEW: orange overlay, job running
  deletedIds: string[];          // NEW: strikethrough + orange, job done, awaiting manual clear
}

// New actions
export class MarkPhotosPendingDeletion {
  static readonly type = '[Photos] Mark Pending Deletion';
  constructor(public ids: string[]) {}
}
export class MarkPhotosDeleted {
  static readonly type = '[Photos] Mark Deleted';
  constructor(public ids: string[]) {}
}
export class ClearPhotosPendingDeletion {
  static readonly type = '[Photos] Clear Pending Deletion';
  constructor(public ids: string[]) {}
}
export class ClearDeletedPhotos {
  static readonly type = '[Photos] Clear Deleted';  // triggered by "Clear N removed" button
}
```

`MarkPhotosDeleted`: moves IDs from `pendingDeletionIds` → `deletedIds` (stays in grid, marked).
`ClearDeletedPhotos`: removes photos with IDs in `deletedIds` from `photos[]`, clears `deletedIds`.

---

## Part 4 — Frontend: Global Task Bar

### GlobalTaskBarComponent

Reads `JobsState.jobs` signal. Zero business logic — pure display.

```typescript
// core/components/global-task-bar/global-task-bar.component.ts
@Component({
  selector: 'app-global-task-bar',
  standalone: true,
})
export class GlobalTaskBarComponent {
  jobs = this.store.selectSignal(JobsState.jobs);
  activeCount = computed(() => this.jobs().filter(j => j.status === 'RUNNING').length);
  expanded = signal(false);
}
```

**UI States:**

```
Hidden: no jobs exist

Collapsed (default):
┌─────────────────────────────┐
│  ⚙ 2 tasks running      ▲  │
└─────────────────────────────┘

Expanded:
┌──────────────────────────────────┐
│  Tasks                        ▼  │
├──────────────────────────────────┤
│  🗑 Deleting photos (iCloud)     │
│  ████████░░░░░  64 / 100         │
│                           [✕]    │
├──────────────────────────────────┤
│  🖼 Generating thumbnails        │
│  ██████░░░░░░░  32 / 50          │
│                           [✕]    │
└──────────────────────────────────┘

Completed row (auto-fades after 5s):
│  ✓ Deletion complete  98 ok / 2 failed │
```

**App-level wiring:**

```html
<!-- app.component.html -->
<router-outlet />
<app-toast-host />
<app-global-task-bar />
```

---

## Part 5 — Frontend: Optimistic UI

### PhotoCardComponent

```html
<div class="photo-card"
     [class.pending-deletion]="isPending()"
     [class.deleted]="isDeleted()">
  <img ... />
  @if (isPending()) {
    <div class="state-overlay">Deleting…</div>
  }
  @if (isDeleted()) {
    <div class="state-overlay deleted-overlay">Removed</div>
  }
</div>
```

```typescript
isPending = computed(() => this.store.selectSignal(PhotosState.pendingDeletionIds)().includes(this.photo().id));
isDeleted = computed(() => this.store.selectSignal(PhotosState.deletedIds)().includes(this.photo().id));
```

```scss
.photo-card.pending-deletion {
  opacity: 0.6;
  outline: 2px solid var(--color-warning-500);
  .state-overlay { background: rgba(251, 146, 60, 0.25); color: var(--color-warning-700); }
}
.photo-card.deleted {
  opacity: 0.45;
  outline: 2px solid var(--color-warning-600);
  .deleted-overlay { background: rgba(234, 88, 12, 0.3); color: var(--color-warning-800); }
}
```

### "Clear Removed" Button

Appears above photo grid when `deletedIds.length > 0`:

```html
@if (deletedCount() > 0) {
  <div class="clear-removed-bar">
    <span>{{ deletedCount() }} photo(s) removed from iCloud</span>
    <button (click)="clearRemoved()">Clear from view</button>
  </div>
}
```

`clearRemoved()` dispatches `ClearDeletedPhotos` action → `PhotosState` removes those photos from `photos[]`.

### BatchActionsBar Guard

```typescript
// in photos.component.ts — thin dispatch only
onDeleteFromICloud(): void {
  const selected = this.store.selectSnapshot(PhotosState.selectedPhotos);
  this.store.dispatch(new StartDeletionJob({
    photoIds: selected.map(p => p.id),  // guard runs inside action
    accountId: selected[0].accountId,
    provider: 'ICLOUD'
  }));
}
```

Guard (filtering `existsOnIcloud`) lives in `JobsState @Action(StartDeletionJob)` — not in component.

---

## Part 6 — Thumbnail Job Migration

`ThumbnailProgressService` (currently local to photos component) migrates to `JobsState`. Component dispatches `TrackJob` with type `THUMBNAIL` when starting a thumbnail job. `GlobalTaskBarComponent` shows it for free.

Optional for first iteration, required for consistency.

---

## Implementation Order

```
Phase 1 — icloud-service (½ day)
  [ ] BatchDeleteRequest Pydantic model
  [ ] POST /photos/batch-delete with asyncio.gather + Semaphore(10)
  [ ] Tests for batch endpoint

Phase 2 — Backend job infrastructure (1 day)
  [ ] DeletionJob + DeletionJobService (mirror ThumbnailJob)
  [ ] DeletionJobController (4 endpoints)
  [ ] JobsController GET /api/jobs + GET /api/jobs/{id}/progress
  [ ] Server-side guard (filter non-eligible photos before job start)
  [ ] ICloudSyncProvider.batchDelete() calling new icloud-service endpoint
  [ ] DeletionProgress includes successfulIds + failedIds in final event
  [ ] Deprecate DELETE /api/photos/icloud → 410 Gone
  [ ] Regenerate openapi.yml

Phase 3 — Frontend stores (1 day)
  [ ] JobsState (StartDeletionJob, TrackJob, UpdateJobProgress, RemoveJob, LoadActiveJobs)
  [ ] PhotosState: add pendingDeletionIds, deletedIds, new actions
  [ ] Wire LoadActiveJobs dispatch in AppComponent ngOnInit
  [ ] Regenerate Orval client from new openapi.yml

Phase 4 — Frontend components (1 day)
  [ ] GlobalTaskBarComponent (fixed bottom-right, collapsible, reads JobsState)
  [ ] Wire into AppComponent
  [ ] PhotoCardComponent: pending + deleted CSS states
  [ ] "Clear removed" bar above photo grid
  [ ] BatchActionsBar: thin dispatch (guard in store)
  [ ] Remove old operationStatus / pendingDeletion signals from photos.component.ts

Phase 5 — Integration + polish (½ day)
  [ ] E2E: select → delete → orange → job complete → strikethrough → clear → gone. But this is only in provider-specific view. On all items view, it won't disappear cause it still be on a disk.
  [ ] Cancel job: clear pendingIds, restore grid
  [ ] Error: clear pendingIds, toast with failed count
  [ ] Migrate thumbnail job to JobsState
  [ ] LoadActiveJobs on tab focus (reconnect SSE after backgrounded)
```

---

## Key Design Decisions & Rationale

| Decision | Rationale |
|---|---|
| Photos stay in grid after deletion (not auto-removed) | Completion ≠ confirmation the user noticed. "Clear N removed" gives explicit control. |
| `deletedIds` separate from `pendingDeletionIds` | Different visual states (pending = job running, deleted = job done awaiting clear). Different lifecycle. |
| Guard in `JobsState` action, not in component | Components stay thin dispatchers. Guard is testable in isolation. Same guard fires regardless of which component triggers deletion. |
| `JobsState` generic (not DeletionState) | Thumbnail jobs and future job types plug in without new store. `type` field distinguishes behavior. |
| `GET /api/jobs` unified endpoint | Frontend needs one call on init to re-attach SSE after navigation or page refresh. Separate per-type endpoints would require N calls. |
| `GET /api/jobs/{id}/progress` routes by jobId | `JobsController` does a lookup across all services — caller doesn't need to know job type to get SSE. |
| In-memory jobs (no DB table) | Deletion jobs are transient. Loss on restart is acceptable — user can re-delete. Matches ThumbnailJob pattern. |
| POST /photos/batch-delete (not DELETE) | HTTP DELETE with body is widely unsupported. POST with explicit action semantics is cleaner. |
| asyncio.gather + Semaphore(10) | Full parallelism inside chunk without hammering iCloud rate limits. Cap tunable. |
| Sequential chunks on backend | iCloud has undocumented rate limits. Parallel chunks of 50 with internal concurrency = speed + stability. |
| Final SSE event includes successfulIds | Frontend knows exactly which IDs to move from pendingDeletionIds → deletedIds without reloading. |
