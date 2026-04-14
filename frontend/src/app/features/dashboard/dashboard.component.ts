import { Component, OnInit, ViewChild, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { DeviceStatusPanelComponent } from './device-status-panel/device-status-panel.component';
import { AccountsPanelComponent } from './accounts-panel/accounts-panel.component';
import { AddAccountModalComponent } from './add-account-modal/add-account-modal.component';
import { SyncSectionComponent } from './sync-section/sync-section.component';
import { ReorganizeSectionComponent } from './reorganize-section/reorganize-section.component';
import { ActiveContextCardComponent } from './active-context-card/active-context-card.component';
import { StorageStatsCardComponent } from './storage-stats-card/storage-stats-card.component';
import { DiskScanningModalComponent } from './disk-scanning-modal/disk-scanning-modal.component';
import { AccountResponse } from '../../core/api/generated/model/accountResponse';

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [
    DeviceStatusPanelComponent,
    AccountsPanelComponent,
    AddAccountModalComponent,
    SyncSectionComponent,
    ReorganizeSectionComponent,
    ActiveContextCardComponent,
    StorageStatsCardComponent,
    DiskScanningModalComponent,
  ],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.scss'
})
export class DashboardComponent implements OnInit {
  @ViewChild(AccountsPanelComponent) accountsPanel!: AccountsPanelComponent;
  @ViewChild(SyncSectionComponent) syncSection!: SyncSectionComponent;
  @ViewChild(DeviceStatusPanelComponent) deviceStatusPanel!: DeviceStatusPanelComponent;

  private route = inject(ActivatedRoute);
  private router = inject(Router);

  showModal = signal(false);
  showScanningModal = signal(false);
  reconnectAccount = signal<AccountResponse | null>(null);

  ngOnInit(): void {
    const scanning = this.route.snapshot.queryParamMap.get('scanning');
    if (scanning === 'true') {
      this.showScanningModal.set(true);
      this.router.navigate([], { replaceUrl: true, queryParams: {} });
    }
  }

  onAddAccountRequested(): void {
    this.reconnectAccount.set(null);
    this.showModal.set(true);
  }

  onReconnectRequested(account: AccountResponse): void {
    this.reconnectAccount.set(account);
    this.showModal.set(true);
  }

  onModalClosed(): void {
    this.showModal.set(false);
    this.reconnectAccount.set(null);
  }

  onAccountAdded(): void {
    this.showModal.set(false);
    this.reconnectAccount.set(null);
    this.accountsPanel.loadAccounts();
    this.syncSection.refresh();
    this.deviceStatusPanel.loadStatuses();
  }

  onScanningModalClosed(): void {
    this.showScanningModal.set(false);
  }
}
