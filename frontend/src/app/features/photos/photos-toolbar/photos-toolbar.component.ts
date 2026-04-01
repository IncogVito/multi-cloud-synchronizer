import { Component, input, output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AccountResponse } from '../../../core/api/generated/model/accountResponse';

@Component({
  selector: 'app-photos-toolbar',
  standalone: true,
  imports: [FormsModule],
  templateUrl: './photos-toolbar.component.html',
  styleUrl: './photos-toolbar.component.scss'
})
export class PhotosToolbarComponent {
  accounts = input<AccountResponse[]>([]);
  selectedAccountId = input('');
  granularity = input<'year' | 'month'>('year');
  syncing = input(false);
  canSync = input(false);

  accountChanged = output<string>();
  granularityChanged = output<'year' | 'month'>();
  syncRequested = output<void>();

  onAccountChange(value: string): void {
    this.accountChanged.emit(value);
  }

  setGranularity(g: 'year' | 'month'): void {
    this.granularityChanged.emit(g);
  }
}
