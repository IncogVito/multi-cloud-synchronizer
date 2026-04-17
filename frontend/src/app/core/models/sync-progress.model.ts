export type SyncPhase =
  | 'FETCHING_METADATA'
  | 'PERSISTING_METADATA'
  | 'COMPARING'
  | 'AWAITING_CONFIRMATION'
  | 'DOWNLOADING'
  | 'DONE'
  | 'ERROR'
  | 'CANCELLED'
  | 'REORGANIZING';

export interface SyncProgressEvent {
  accountId: string;
  phase: SyncPhase;
  totalOnCloud: number;
  metadataFetched: number;
  synced: number;
  failed: number;
  pending: number;
  percentComplete: number;
  currentFile?: string;
  timestamp: string;
  diskFreeBytes?: number;
  diskPhotoCount?: number;
}

export interface SyncStartResponse {
  accountId: string;
  phase: SyncPhase;
  message: string;
  startedAt: string;
}
