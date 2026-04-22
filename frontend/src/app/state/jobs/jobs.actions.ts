export class StartDeletionJob {
  static readonly type = '[Jobs] Start Deletion Job';
  constructor(public payload: { accountId: string; photoIds: string[]; provider: 'ICLOUD' | 'IPHONE' }) {}
}

export class TrackJob {
  static readonly type = '[Jobs] Track Job';
  constructor(
    public jobId: string,
    public type: 'DELETION' | 'THUMBNAIL',
    public label: string,
    public affectedPhotoIds?: string[],
  ) {}
}

export class UpdateJobProgress {
  static readonly type = '[Jobs] Update Progress';
  constructor(public jobId: string, public patch: Partial<import('./jobs.state').Job>) {}
}

export class RemoveJob {
  static readonly type = '[Jobs] Remove';
  constructor(public jobId: string) {}
}

export class LoadActiveJobs {
  static readonly type = '[Jobs] Load Active';
}

export class CancelJob {
  static readonly type = '[Jobs] Cancel';
  constructor(public jobId: string) {}
}
