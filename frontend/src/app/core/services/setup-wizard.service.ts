import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';
import {
  BrowseResponse,
  DiskScanResult,
  ReorganizeResult,
  SyncConfigRequest
} from '../models/sync-config.model';

@Injectable({ providedIn: 'root' })
export class SetupWizardService {

  constructor(private api: ApiService) {}

  browse(path?: string): Observable<BrowseResponse> {
    const query = path ? `?path=${encodeURIComponent(path)}` : '';
    return this.api.get<BrowseResponse>(`/setup/browse${query}`);
  }

  scan(path: string): Observable<DiskScanResult> {
    return this.api.get<DiskScanResult>(`/setup/scan?path=${encodeURIComponent(path)}`);
  }

  saveSyncConfig(accountId: string, config: SyncConfigRequest): Observable<{ success: boolean }> {
    return this.api.put<{ success: boolean }>(`/accounts/${accountId}/sync-config`, config);
  }

  reorganize(accountId: string, dryRun: boolean): Observable<ReorganizeResult> {
    return this.api.post<ReorganizeResult>(`/accounts/${accountId}/reorganize?dryRun=${dryRun}`, {});
  }
}
