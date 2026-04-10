export type SyncPhase =
  | 'FETCHING_METADATA'
  | 'COMPARING'
  | 'AWAITING_CONFIRMATION'
  | 'DOWNLOADING'
  | 'DONE'
  | 'ERROR';

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
}

export interface SyncStartResponse {
  accountId: string;
  phase: SyncPhase;
  message: string;
  startedAt: string;
}
