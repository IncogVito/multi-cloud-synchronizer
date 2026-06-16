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
import { Store } from '@ngxs/store';

describe('TasksComponent', () => {
  let listHistory: ReturnType<typeof vi.fn>;
  let activeAccountId: ReturnType<typeof vi.fn>;

  function create(activeId: string | null): TasksComponent {
    listHistory = vi.fn().mockReturnValue(of({ tasks: [], totalPages: 0 }));
    activeAccountId = vi.fn().mockReturnValue(activeId);

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
});
