import '@angular/compiler';
import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { Store } from '@ngxs/store';
import { vi } from 'vitest';
import { GlobalTaskBarComponent } from './global-task-bar.component';
import { Job } from '../../../state/jobs/jobs.state';

function makeStoreMock(initialJobs: Job[] = []) {
  const jobsSignal = signal<Job[]>(initialJobs);
  return {
    signal: jobsSignal,
    store: {
      selectSignal: vi.fn().mockReturnValue(jobsSignal),
      dispatch: vi.fn(),
    },
  };
}

async function setup(initialJobs: Job[] = []) {
  const { signal: jobsSignal, store: mockStore } = makeStoreMock(initialJobs);

  await TestBed.configureTestingModule({
    imports: [GlobalTaskBarComponent],
    providers: [{ provide: Store, useValue: mockStore }],
  }).compileComponents();

  const fixture = TestBed.createComponent(GlobalTaskBarComponent);
  fixture.detectChanges();

  return { fixture, el: fixture.nativeElement as HTMLElement, jobsSignal, fixture: fixture };
}

describe('GlobalTaskBarComponent', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('renders nothing when no jobs exist', async () => {
    const { el } = await setup([]);
    expect(el.querySelector('.task-bar')).toBeNull();
  });

  it('renders task bar when a running job exists', async () => {
    const { el } = await setup([
      { jobId: 'j1', type: 'DELETION', status: 'RUNNING', label: 'Deleting', total: 10, done: 3, failed: 0 },
    ]);
    expect(el.querySelector('.task-bar')).not.toBeNull();
  });

  it('shows "2 tasks running" in header for two running jobs', async () => {
    const { el } = await setup([
      { jobId: 'j1', type: 'DELETION', status: 'RUNNING', label: 'D1', total: 5, done: 0, failed: 0 },
      { jobId: 'j2', type: 'THUMBNAIL', status: 'RUNNING', label: 'T1', total: 3, done: 0, failed: 0 },
    ]);
    expect(el.querySelector('.task-bar-header')?.textContent).toContain('2 tasks running');
  });

  it('shows "1 task running" singular for a single running job', async () => {
    const { el } = await setup([
      { jobId: 'j1', type: 'DELETION', status: 'RUNNING', label: 'D', total: 5, done: 0, failed: 0 },
    ]);
    expect(el.querySelector('.task-bar-header')?.textContent).toContain('1 task running');
  });

  it('shows "All tasks done" when all jobs are completed', async () => {
    const { el } = await setup([
      { jobId: 'j1', type: 'DELETION', status: 'COMPLETED', label: 'D', total: 5, done: 5, failed: 0 },
    ]);
    expect(el.querySelector('.task-bar-header')?.textContent).toContain('All tasks done');
  });

  it('shows deletion icon 🗑 for DELETION type', async () => {
    const { el } = await setup([
      { jobId: 'j1', type: 'DELETION', status: 'RUNNING', label: 'Deleting', total: 1, done: 0, failed: 0 },
    ]);
    expect(el.querySelector('.task-icon')?.textContent?.trim()).toBe('🗑');
  });

  it('shows thumbnail icon 🖼 for THUMBNAIL type', async () => {
    const { el } = await setup([
      { jobId: 'j1', type: 'THUMBNAIL', status: 'RUNNING', label: 'Thumbnails', total: 5, done: 0, failed: 0 },
    ]);
    expect(el.querySelector('.task-icon')?.textContent?.trim()).toBe('🖼');
  });

  it('shows progress counts for running job', async () => {
    const { el } = await setup([
      { jobId: 'j1', type: 'DELETION', status: 'RUNNING', label: 'D', total: 10, done: 7, failed: 0 },
    ]);
    expect(el.querySelector('.task-counts')?.textContent).toContain('7 / 10');
  });

  it('shows done row with ok/failed counts for completed job', async () => {
    const { el } = await setup([
      { jobId: 'j1', type: 'DELETION', status: 'COMPLETED', label: 'D', total: 6, done: 5, failed: 1 },
    ]);
    const doneRow = el.querySelector('.task-done-row');
    expect(doneRow?.textContent).toContain('5 ok');
    expect(doneRow?.textContent).toContain('1 failed');
  });

  it('hides task list on header click (collapse)', async () => {
    const { fixture, el } = await setup([
      { jobId: 'j1', type: 'DELETION', status: 'RUNNING', label: 'D', total: 1, done: 0, failed: 0 },
    ]);
    expect(el.querySelector('.task-list')).not.toBeNull();

    (el.querySelector('.task-bar-header') as HTMLElement).click();
    fixture.detectChanges();

    expect(el.querySelector('.task-list')).toBeNull();
  });

  it('re-opens task list on second header click (expand)', async () => {
    const { fixture, el } = await setup([
      { jobId: 'j1', type: 'DELETION', status: 'RUNNING', label: 'D', total: 1, done: 0, failed: 0 },
    ]);
    const header = el.querySelector('.task-bar-header') as HTMLElement;
    header.click();
    fixture.detectChanges();
    header.click();
    fixture.detectChanges();

    expect(el.querySelector('.task-list')).not.toBeNull();
  });

  it('dispatches CancelJob when cancel button is clicked', async () => {
    const { store: mockStore } = makeStoreMock([
      { jobId: 'j1', type: 'DELETION', status: 'RUNNING', label: 'D', total: 5, done: 0, failed: 0 },
    ]);

    await TestBed.configureTestingModule({
      imports: [GlobalTaskBarComponent],
      providers: [{ provide: Store, useValue: mockStore }],
    }).compileComponents();

    const fixture = TestBed.createComponent(GlobalTaskBarComponent);
    fixture.detectChanges();

    const cancelBtn = fixture.nativeElement.querySelector('.cancel-btn') as HTMLElement;
    cancelBtn?.click();

    expect(mockStore.dispatch).toHaveBeenCalled();
  });
});
