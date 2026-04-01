import { Component, ViewChild } from '@angular/core';
import { DeviceStatusPanelComponent } from './device-status-panel/device-status-panel.component';
import { AccountsPanelComponent } from './accounts-panel/accounts-panel.component';
import { AddAccountModalComponent } from './add-account-modal/add-account-modal.component';

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [DeviceStatusPanelComponent, AccountsPanelComponent, AddAccountModalComponent],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.scss'
})
export class DashboardComponent {
  @ViewChild(AccountsPanelComponent) accountsPanel!: AccountsPanelComponent;

  showModal = false;

  onAddAccountRequested(): void {
    this.showModal = true;
  }

  onModalClosed(): void {
    this.showModal = false;
  }

  onAccountAdded(): void {
    this.showModal = false;
    this.accountsPanel.loadAccounts();
  }
}
