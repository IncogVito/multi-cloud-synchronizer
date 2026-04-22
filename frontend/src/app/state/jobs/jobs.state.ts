import { Injectable } from '@angular/core';
import { Action, Selector, State, StateContext, Store } from '@ngxs/store';
import { HttpClient } from '@angular/common/http';
import { catchError, EMPTY, firstValueFrom } from 'rxjs';
import { AuthService } from '../../core/services/auth.service';
import {
  CancelJob,
  LoadActiveJobs,
  RemoveJob,
  StartDeletionJob,
  TrackJob,
  UpdateJobProgress,
} from './jobs.actions';
import {
  ClearPhotosPendingDeletion,
  MarkPhotosDeleted,
  MarkPhotosPendingDeletion,
} from '../photos/photos.actions';

export interface Job {
  jobId: string;
  type: 'DELETION' | 'THUMBNAIL';
  status: 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'CANCELLED' | 'FAILED';
  label: string;
  total: number;
  done: number;
  failed: number;
  affectedPhotoIds?: string[];
}

export interface JobsStateModel {
  jobs: Job[];
}

@State<JobsStateModel>({
  name: 'jobs',
  defaults: { jobs: [] },
})
@Injectable()
export class JobsState {
  private abortControllers = new Map<string, AbortController>();

  constructor(
    private http: HttpClient,
    private auth: AuthService,
    private store: Store,
  ) {}

  @Selector()
  static jobs(state: JobsStateModel): Job[] {
    return state.jobs;
  }

  @Selector()
  static activeJobs(state: JobsStateModel): Job[] {
    return state.jobs.filter(j => j.status === 'RUNNING' || j.status === 'QUEUED');
  }

  @Action(StartDeletionJob)
  async startDeletionJob(ctx: StateContext<JobsStateModel>, action: StartDeletionJob) {
    const { accountId, photoIds, provider } = action.payload;

    const creds = this.auth.getCredentials();
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (creds) headers['Authorization'] = `Basic ${creds.encoded}`;

    try {
      const res = await fetch('/api/photos/deletion-jobs', {
        method: 'POST',
        headers,
        body: JSON.stringify({ accountId, photoIds, provider }),
      });
      if (!res.ok) return;
      const data: { jobId: string; accepted: number; skipped: number } = await res.json();
      const acceptedIds = photoIds.slice(0, data.accepted);

      ctx.dispatch(new MarkPhotosPendingDeletion(acceptedIds));
      ctx.dispatch(new TrackJob(data.jobId, 'DELETION', `Deleting photos (${provider})`, acceptedIds));
    } catch (e) {
      console.error('Failed to start deletion job', e);
    }
  }

  @Action(TrackJob)
  trackJob(ctx: StateContext<JobsStateModel>, action: TrackJob) {
    const job: Job = {
      jobId: action.jobId,
      type: action.type,
      status: 'RUNNING',
      label: action.label,
      total: 0,
      done: 0,
      failed: 0,
      affectedPhotoIds: action.affectedPhotoIds,
    };

    ctx.patchState({ jobs: [...ctx.getState().jobs, job] });
    this.connectSSE(ctx, action.jobId, action.type, action.affectedPhotoIds ?? []);
  }

  @Action(UpdateJobProgress)
  updateJobProgress(ctx: StateContext<JobsStateModel>, action: UpdateJobProgress) {
    ctx.patchState({
      jobs: ctx.getState().jobs.map(j =>
        j.jobId === action.jobId ? { ...j, ...action.patch } : j,
      ),
    });
  }

  @Action(RemoveJob)
  removeJob(ctx: StateContext<JobsStateModel>, action: RemoveJob) {
    this.abortControllers.get(action.jobId)?.abort();
    this.abortControllers.delete(action.jobId);
    ctx.patchState({ jobs: ctx.getState().jobs.filter(j => j.jobId !== action.jobId) });
  }

  @Action(LoadActiveJobs)
  async loadActiveJobs(ctx: StateContext<JobsStateModel>) {
    const creds = this.auth.getCredentials();
    const headers: Record<string, string> = {};
    if (creds) headers['Authorization'] = `Basic ${creds.encoded}`;

    try {
      const res = await fetch('/api/jobs', { headers });
      if (!res.ok) return;
      const data: { jobs: Array<{ jobId: string; type: string; status: string; total: number; done: number; failed: number; label: string }> } = await res.json();

      for (const j of data.jobs) {
        const exists = ctx.getState().jobs.some(existing => existing.jobId === j.jobId);
        if (!exists && (j.status === 'RUNNING' || j.status === 'QUEUED')) {
          ctx.dispatch(new TrackJob(j.jobId, j.type as Job['type'], j.label));
        }
      }
    } catch (e) {
      console.error('Failed to load active jobs', e);
    }
  }

  @Action(CancelJob)
  async cancelJob(ctx: StateContext<JobsStateModel>, action: CancelJob) {
    const job = ctx.getState().jobs.find(j => j.jobId === action.jobId);
    this.abortControllers.get(action.jobId)?.abort();

    const creds = this.auth.getCredentials();
    const headers: Record<string, string> = {};
    if (creds) headers['Authorization'] = `Basic ${creds.encoded}`;

    try {
      await fetch(`/api/photos/deletion-jobs/${action.jobId}`, { method: 'DELETE', headers });
    } catch {}

    if (job?.affectedPhotoIds?.length) {
      ctx.dispatch(new ClearPhotosPendingDeletion(job.affectedPhotoIds));
    }
    ctx.dispatch(new RemoveJob(action.jobId));
  }

  private connectSSE(
    ctx: StateContext<JobsStateModel>,
    jobId: string,
    type: Job['type'],
    affectedPhotoIds: string[],
  ): void {
    const controller = new AbortController();
    this.abortControllers.set(jobId, controller);

    const creds = this.auth.getCredentials();
    const headers: Record<string, string> = { Accept: 'text/event-stream' };
    if (creds) headers['Authorization'] = `Basic ${creds.encoded}`;

    const endpoint = type === 'DELETION'
      ? `/api/photos/deletion-jobs/${jobId}/progress`
      : `/api/photos/thumbnail-jobs/${jobId}/progress`;

    fetch(endpoint, { headers, signal: controller.signal })
      .then(r => this.readSSEStream(r, ctx, jobId, type, affectedPhotoIds))
      .catch(err => {
        if (err?.name !== 'AbortError') {
          ctx.dispatch(new UpdateJobProgress(jobId, { status: 'FAILED' }));
        }
      });
  }

  private async readSSEStream(
    response: Response,
    ctx: StateContext<JobsStateModel>,
    jobId: string,
    type: Job['type'],
    affectedPhotoIds: string[],
  ): Promise<void> {
    const reader = response.body!.getReader();
    const decoder = new TextDecoder();
    let buffer = '';

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split('\n');
      buffer = lines.pop() ?? '';
      for (const line of lines) {
        this.handleSSELine(line, ctx, jobId, type, affectedPhotoIds);
      }
    }
  }

  private handleSSELine(
    line: string,
    ctx: StateContext<JobsStateModel>,
    jobId: string,
    type: Job['type'],
    affectedPhotoIds: string[],
  ): void {
    if (!line.startsWith('data:')) return;
    try {
      const event = JSON.parse(line.slice(5).trim());

      if (type === 'DELETION') {
        const patch: Partial<Job> = {
          total: event.total ?? 0,
          done: event.deleted ?? 0,
          failed: event.failed ?? 0,
          status: event.done ? 'COMPLETED' : 'RUNNING',
        };
        ctx.dispatch(new UpdateJobProgress(jobId, patch));

        if (event.done) {
          const successIds: string[] = event.successfulIds ?? [];
          if (successIds.length) ctx.dispatch(new MarkPhotosDeleted(successIds));

          const failedIds: string[] = event.failedIds ?? [];
          const notDeleted = affectedPhotoIds.filter(id => !successIds.includes(id));
          if (notDeleted.length) ctx.dispatch(new ClearPhotosPendingDeletion(notDeleted));

          setTimeout(() => ctx.dispatch(new RemoveJob(jobId)), 5000);
        }
      } else {
        const patch: Partial<Job> = {
          total: event.total ?? 0,
          done: event.processed ?? 0,
          failed: event.errors ?? 0,
          status: event.done ? 'COMPLETED' : 'RUNNING',
        };
        ctx.dispatch(new UpdateJobProgress(jobId, patch));
        if (event.done) setTimeout(() => ctx.dispatch(new RemoveJob(jobId)), 5000);
      }
    } catch {}
  }
}
