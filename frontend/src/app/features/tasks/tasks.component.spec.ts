import '@angular/compiler';
import { vi, describe, it, expect, beforeEach } from 'vitest';
import { of } from 'rxjs';
import { TestBed } from '@angular/core/testing';
import { TasksComponent } from './tasks.component';
import { JobsService } from '../../core/api/generated/jobs/jobs.service';
import { AccountSessionService } from '../../core/services/account-session.service';
import { RepairThumbnailsService } from '../../core/services/repair-thumbnails.service';
import { RepairIPhoneService } from '../../core/services/repair-iphone.service';
import { BackupDatabaseService } from '../../core/services/backup-database.service';
import { DeletePendingService } from '../../core/services/delete-pending.service';
import { MergeDuplicatesService } from '../../core/services/merge-duplicates.service';
import { OrphanPhotosService } from '../../core/services/orphan-photos.service';
import { Store } from '@ngxs/store';

describe('TasksComponent', () => {
  let listHistory: ReturnType<typeof vi.fn>;
  let activeAccountId: ReturnType<typeof vi.fn>;
  let orphan: {
    orphanCount: ReturnType<typeof vi.fn>;
    running: ReturnType<typeof vi.fn>;
    progress: ReturnType<typeof vi.fn>;
    refreshCount: ReturnType<typeof vi.fn>;
    startJob: ReturnType<typeof vi.fn>;
  };

  function create(activeId: string | null, orphanOverrides: Partial<{ count: number; running: boolean; progress: unknown }> = {}): TasksComponent {
    listHistory = vi.fn().mockReturnValue(of({ tasks: [], totalPages: 0 }));
    activeAccountId = vi.fn().mockReturnValue(activeId);
    orphan = {
      orphanCount: vi.fn().mockReturnValue(orphanOverrides.count ?? 0),
      running: vi.fn().mockReturnValue(orphanOverrides.running ?? false),
      progress: vi.fn().mockReturnValue(orphanOverrides.progress ?? null),
      refreshCount: vi.fn().mockResolvedValue(undefined),
      startJob: vi.fn().mockResolvedValue(undefined),
    };

    TestBed.configureTestingModule({
      providers: [
        { provide: JobsService, useValue: { listHistory, getTaskDetail: vi.fn() } },
        { provide: AccountSessionService, useValue: { activeAccountId } },
        { provide: Store, useValue: { selectSnapshot: vi.fn().mockReturnValue([]) } },
        { provide: RepairThumbnailsService, useValue: { running: () => false, progress: () => null } },
        { provide: RepairIPhoneService, useValue: { running: () => false, progress: () => null } },
        { provide: BackupDatabaseService, useValue: { running: () => false, progress: () => null } },
        { provide: DeletePendingService, useValue: { running: () => false, error: () => null, lastDeleted: () => null } },
        { provide: MergeDuplicatesService, useValue: { running: () => false, progress: () => null } },
        { provide: OrphanPhotosService, useValue: orphan },
      ],
    });
    return TestBed.runInInjectionContext(() => new TasksComponent());
  }

  beforeEach(() => TestBed.resetTestingModule());

  it('passes activeAccountId from AccountSessionService to listHistory', () => {
    const component = create('acc-1');
    (component as any).load(0);
    expect(listHistory).toHaveBeenCalledWith(
      expect.objectContaining({ accountId: 'acc-1' }),
    );
  });

  it('omits accountId when no active account', () => {
    const component = create(null);
    (component as any).load(0);
    expect(listHistory.mock.calls[0][0]).not.toHaveProperty('accountId');
  });

  it('combines accountId with type and status filters', () => {
    const component = create('acc-9');
    component.typeFilter.set('SYNC');
    component.statusFilter.set('RUNNING');
    (component as any).load(0);
    expect(listHistory).toHaveBeenCalledWith(
      expect.objectContaining({ accountId: 'acc-9', type: 'SYNC', status: 'RUNNING' }),
    );
  });

  describe('orphan photos action', () => {
    it('ngOnInit refreshes orphan count for the active account', () => {
      const component = create('acc-1');
      component.ngOnInit();
      expect(orphan.refreshCount).toHaveBeenCalledWith('acc-1');
      component.ngOnDestroy();
    });

    it('ngOnInit does not refresh count when no active account', () => {
      const component = create(null);
      component.ngOnInit();
      expect(orphan.refreshCount).not.toHaveBeenCalled();
      component.ngOnDestroy();
    });

    it('assignOrphanPhotos starts the job with the active account', () => {
      const component = create('acc-7', { count: 3 });
      component.assignOrphanPhotos();
      expect(orphan.startJob).toHaveBeenCalledWith('acc-7');
    });

    it('assignOrphanPhotos is a no-op without an active account', () => {
      const component = create(null, { count: 3 });
      component.assignOrphanPhotos();
      expect(orphan.startJob).not.toHaveBeenCalled();
    });

    it('orphanDoneMessage reports the assigned count when done', () => {
      const component = create('acc-1', { progress: { processed: 4, total: 4, assigned: 4, done: true } });
      expect(component.orphanDoneMessage()).toContain('4');
    });

    it('orphanDoneMessage is null while running (not done)', () => {
      const component = create('acc-1', { progress: { processed: 1, total: 4, assigned: 1, done: false } });
      expect(component.orphanDoneMessage()).toBeNull();
    });

    it('orphanDoneMessage handles the zero-assigned completion', () => {
      const component = create('acc-1', { progress: { processed: 0, total: 0, assigned: 0, done: true } });
      expect(component.orphanDoneMessage()).toContain('Brak');
    });
  });
});
