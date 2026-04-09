export interface AppContext {
  storageDeviceId: string;
  storageDeviceLabel: string | null;
  mountPoint: string;
  basePath: string;
  relativePath: string;
  freeBytes: number | null;
  setAt: string;
  degraded: boolean;
}

export interface BrowseEntry {
  name: string;
  absolutePath: string;
  relativePath: string;
}

export interface BrowseContextResponse {
  mountPoint: string;
  currentAbsolute: string;
  currentRelative: string;
  entries: BrowseEntry[];
}
