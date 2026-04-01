import { Component, Input, Output, EventEmitter } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AccountResponse } from '../../../core/api/generated/model/accountResponse';

@Component({
  selector: 'app-photos-toolbar',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './photos-toolbar.component.html',
  styleUrl: './photos-toolbar.component.scss'
})
export class PhotosToolbarComponent {
  @Input() accounts: AccountResponse[] = [];
  @Input() selectedAccountId = '';
  @Input() granularity: 'year' | 'month' = 'year';
  @Input() syncing = false;
  @Input() canSync = false;

  @Output() accountChanged = new EventEmitter<string>();
  @Output() granularityChanged = new EventEmitter<'year' | 'month'>();
  @Output() syncRequested = new EventEmitter<void>();

  onAccountChange(value: string): void {
    this.accountChanged.emit(value);
  }

  setGranularity(g: 'year' | 'month'): void {
    this.granularityChanged.emit(g);
  }
}
