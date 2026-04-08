export type OrganizeBy = 'YEAR' | 'MONTH';

export interface BrowseEntry {
  name: string;
  path: string;
  dir: boolean;
  childCount: number;
}

export interface BrowseResponse {
  path: string;
  entries: BrowseEntry[];
}

export interface DiskScanResult {
  totalFiles: number;
  byExtension: Record<string, number>;
  deepestLevel: number;
}

export interface SyncConfigRequest {
  syncFolderPath: string;
  storageDeviceId: string | null;
  organizeBy: OrganizeBy;
}

export interface MovePreview {
  from: string;
  to: string;
}

export interface ReorganizeResult {
  moved: number;
  skipped: number;
  errors: number;
  dryRun: boolean;
  sampleMoves: MovePreview[];
}

export interface WizardState {
  accountId: string | null;
  deviceId: string | null;
  deviceLabel: string | null;
  selectedFolder: string | null;
  scanResult: DiskScanResult | null;
  organizeBy: OrganizeBy;
}
