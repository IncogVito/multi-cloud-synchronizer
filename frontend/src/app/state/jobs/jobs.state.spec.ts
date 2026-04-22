import '@angular/compiler';
import { vi } from 'vitest';
import { JobsState, JobsStateModel, Job } from './jobs.state';
import { RemoveJob, TrackJob, UpdateJobProgress } from './jobs.actions';

const defaults: JobsStateModel = { jobs: [] };

function makeCtx(stateOverride: Partial<JobsStateModel> = {}) {
  let state: JobsStateModel = { ...defaults, ...stateOverride };
  return {
    getState: () => state,
    patchState: vi.fn((patch: Partial<JobsStateModel>) => {
      state = { ...state, ...patch };
    }),
    setState: vi.fn(),
    dispatch: vi.fn().mockReturnValue({ toPromise: () => Promise.resolve() }),
    currentState: () => state,
  };
}

const mockHttp = {} as any;
const mockAuth = { getCredentials: () => null } as any;
const mockStore = { dispatch: vi.fn() } as any;
const jobsStateInstance = new JobsState(mockHttp, mockAuth, mockStore);

describe('JobsState — UpdateJobProgress', () => {
  it('patches matched job fields', () => {
    const existing: Job = {
      jobId: 'j1', type: 'DELETION', status: 'RUNNING',
      label: 'Deleting', total: 10, done: 0, failed: 0,
    };
    const ctx = makeCtx({ jobs: [existing] });

    jobsStateInstance.updateJobProgress(ctx as any, new UpdateJobProgress('j1', { done: 5, total: 10 }));

    const job = ctx.currentState().jobs.find(j => j.jobId === 'j1');
    expect(job?.done).toBe(5);
    expect(job?.total).toBe(10);
  });

  it('does not touch other jobs', () => {
    const j1: Job = { jobId: 'j1', type: 'DELETION', status: 'RUNNING', label: 'D', total: 10, done: 0, failed: 0 };
    const j2: Job = { jobId: 'j2', type: 'THUMBNAIL', status: 'RUNNING', label: 'T', total: 5, done: 0, failed: 0 };
    const ctx = makeCtx({ jobs: [j1, j2] });

    jobsStateInstance.updateJobProgress(ctx as any, new UpdateJobProgress('j1', { done: 7 }));

    const j2After = ctx.currentState().jobs.find(j => j.jobId === 'j2');
    expect(j2After?.done).toBe(0);
  });

  it('is a no-op for unknown jobId', () => {
    const ctx = makeCtx({ jobs: [] });
    jobsStateInstance.updateJobProgress(ctx as any, new UpdateJobProgress('nope', { done: 99 }));

    expect(ctx.currentState().jobs).toHaveLength(0);
  });

  it('can update status to COMPLETED', () => {
    const j: Job = { jobId: 'j1', type: 'DELETION', status: 'RUNNING', label: 'D', total: 5, done: 5, failed: 0 };
    const ctx = makeCtx({ jobs: [j] });

    jobsStateInstance.updateJobProgress(ctx as any, new UpdateJobProgress('j1', { status: 'COMPLETED' }));

    expect(ctx.currentState().jobs[0].status).toBe('COMPLETED');
  });
});

describe('JobsState — RemoveJob', () => {
  it('removes job from state by jobId', () => {
    const j: Job = { jobId: 'j1', type: 'DELETION', status: 'RUNNING', label: 'D', total: 1, done: 0, failed: 0 };
    const ctx = makeCtx({ jobs: [j] });

    jobsStateInstance.removeJob(ctx as any, new RemoveJob('j1'));

    expect(ctx.currentState().jobs.find(x => x.jobId === 'j1')).toBeUndefined();
  });

  it('leaves other jobs intact', () => {
    const j1: Job = { jobId: 'j1', type: 'DELETION', status: 'RUNNING', label: 'D', total: 1, done: 0, failed: 0 };
    const j2: Job = { jobId: 'j2', type: 'THUMBNAIL', status: 'RUNNING', label: 'T', total: 5, done: 0, failed: 0 };
    const ctx = makeCtx({ jobs: [j1, j2] });

    jobsStateInstance.removeJob(ctx as any, new RemoveJob('j1'));

    expect(ctx.currentState().jobs).toHaveLength(1);
    expect(ctx.currentState().jobs[0].jobId).toBe('j2');
  });

  it('is a no-op for unknown jobId', () => {
    const j: Job = { jobId: 'j1', type: 'DELETION', status: 'RUNNING', label: 'D', total: 1, done: 0, failed: 0 };
    const ctx = makeCtx({ jobs: [j] });

    jobsStateInstance.removeJob(ctx as any, new RemoveJob('nonexistent'));

    expect(ctx.currentState().jobs).toHaveLength(1);
  });
});

describe('JobsState — TrackJob', () => {
  it('adds job to state with RUNNING status', () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('no SSE in test')));
    const ctx = makeCtx();

    jobsStateInstance.trackJob(ctx as any, new TrackJob('j1', 'DELETION', 'Deleting (ICLOUD)', ['p1']));

    const job = ctx.currentState().jobs.find(j => j.jobId === 'j1');
    expect(job).toBeDefined();
    expect(job?.status).toBe('RUNNING');
    expect(job?.type).toBe('DELETION');
    expect(job?.affectedPhotoIds).toEqual(['p1']);
    vi.restoreAllMocks();
  });

  it('initializes done, total, failed to zero', () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('no SSE')));
    const ctx = makeCtx();

    jobsStateInstance.trackJob(ctx as any, new TrackJob('j1', 'THUMBNAIL', 'Thumbnails'));

    const job = ctx.currentState().jobs[0];
    expect(job.done).toBe(0);
    expect(job.total).toBe(0);
    expect(job.failed).toBe(0);
    vi.restoreAllMocks();
  });
});

describe('JobsState selectors', () => {
  it('jobs returns all jobs', () => {
    const jobs: Job[] = [
      { jobId: 'j1', type: 'DELETION', status: 'RUNNING', label: 'D', total: 5, done: 2, failed: 0 },
      { jobId: 'j2', type: 'THUMBNAIL', status: 'COMPLETED', label: 'T', total: 3, done: 3, failed: 0 },
    ];
    expect(JobsState.jobs({ jobs })).toHaveLength(2);
  });

  it('activeJobs filters out non-running jobs', () => {
    const jobs: Job[] = [
      { jobId: 'j1', type: 'DELETION', status: 'RUNNING', label: 'D', total: 5, done: 2, failed: 0 },
      { jobId: 'j2', type: 'THUMBNAIL', status: 'COMPLETED', label: 'T', total: 3, done: 3, failed: 0 },
      { jobId: 'j3', type: 'DELETION', status: 'FAILED', label: 'D2', total: 2, done: 0, failed: 2 },
    ];
    const active = JobsState.activeJobs({ jobs });
    expect(active).toHaveLength(1);
    expect(active[0].jobId).toBe('j1');
  });

  it('activeJobs includes QUEUED jobs', () => {
    const jobs: Job[] = [
      { jobId: 'j1', type: 'DELETION', status: 'QUEUED', label: 'D', total: 0, done: 0, failed: 0 },
    ];
    const active = JobsState.activeJobs({ jobs });
    expect(active).toHaveLength(1);
  });
});
