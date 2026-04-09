import { Component, ViewChild, signal } from '@angular/core';
import { DeviceStatusPanelComponent } from './device-status-panel/device-status-panel.component';
import { AccountsPanelComponent } from './accounts-panel/accounts-panel.component';
import { AddAccountModalComponent } from './add-account-modal/add-account-modal.component';
import { SyncSectionComponent } from './sync-section/sync-section.component';
import { ActiveContextCardComponent } from './active-context-card/active-context-card.component';

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [DeviceStatusPanelComponent, AccountsPanelComponent, AddAccountModalComponent, SyncSectionComponent, ActiveContextCardComponent],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.scss'
})
export class DashboardComponent {
  @ViewChild(AccountsPanelComponent) accountsPanel!: AccountsPanelComponent;

  showModal = signal(false);

  onAddAccountRequested(): void {
    this.showModal.set(true);
  }

  onModalClosed(): void {
    this.showModal.set(false);
  }

  onAccountAdded(): void {
    this.showModal.set(false);
    this.accountsPanel.loadAccounts();
  }
}
