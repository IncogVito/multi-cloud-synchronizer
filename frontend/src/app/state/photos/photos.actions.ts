export class LoadMonthsSummary {
  static readonly type = '[Photos] Load months summary';
  constructor(
    public readonly storageDeviceId: string,
    public readonly accountId?: string,
  ) {}
}

export class LoadPhotos {
  static readonly type = '[Photos] Load first page';
  constructor(
    public readonly storageDeviceId: string,
    public readonly yearMonth?: string,
    public readonly year?: string,
  ) {}
}

export class LoadMorePhotos {
  static readonly type = '[Photos] Load next page';
}

export class SetActiveMonth {
  static readonly type = '[Photos] Set active month';
  /**
   * @param yearMonth ISO year-month string "YYYY-MM", or null to clear the filter.
   * Dispatching also triggers LoadPhotos for the new month if not already cached.
   */
  constructor(public readonly yearMonth: string | null) {}
}

export class ResetPhotos {
  static readonly type = '[Photos] Reset — clear loaded photos and pagination';
}
