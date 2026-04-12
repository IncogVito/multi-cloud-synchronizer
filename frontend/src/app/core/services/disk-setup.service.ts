import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface DriveStatus {
  mounted: boolean;
  drivePath: string | null;
  drivePathHost: string | null;
  freeBytes: number | null;
  deviceId: string | null;
  label: string | null;
}

export interface DiskInfo {
  name: string;
  path: string;
  size: string;
  type: string;
  mountpoint: string | null;
  label: string | null;
  vendor: string | null;
  model: string | null;
}

@Injectable({
  providedIn: 'root'
})
export class DiskSetupService {
  private base = `${environment.apiUrl}/setup`;

  constructor(private http: HttpClient) {}

  getStatus(): Observable<DriveStatus> {
    return this.http.get<DriveStatus>(`${this.base}/status`);
  }

  listDisks(): Observable<DiskInfo[]> {
    return this.http.get<DiskInfo[]>(`${this.base}/disks`);
  }

  mount(device: string): Observable<DriveStatus> {
    return this.http.post<DriveStatus>(`${this.base}/mount`, { device });
  }

  unmount(): Observable<{ success: boolean }> {
    return this.http.post<{ success: boolean }>(`${this.base}/unmount`, {});
  }
}
