import { Component, ViewChild, signal } from '@angular/core';
import { DeviceStatusPanelComponent } from './device-status-panel/device-status-panel.component';
import { AccountsPanelComponent } from './accounts-panel/accounts-panel.component';
import { AddAccountModalComponent } from './add-account-modal/add-account-modal.component';
import { SyncSectionComponent } from './sync-section/sync-section.component';
import { ActiveContextCardComponent } from './active-context-card/active-context-card.component';
import { AccountResponse } from '../../core/api/generated/model/accountResponse';

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [DeviceStatusPanelComponent, AccountsPanelComponent, AddAccountModalComponent, SyncSectionComponent, ActiveContextCardComponent],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.scss'
})
export class DashboardComponent {
  @ViewChild(AccountsPanelComponent) accountsPanel!: AccountsPanelComponent;
  @ViewChild(SyncSectionComponent) syncSection!: SyncSectionComponent;
  @ViewChild(DeviceStatusPanelComponent) deviceStatusPanel!: DeviceStatusPanelComponent;

  showModal = signal(false);
  reconnectAccount = signal<AccountResponse | null>(null);

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
}
