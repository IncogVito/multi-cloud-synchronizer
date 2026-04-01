import { Component, Input, Output, EventEmitter } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-batch-actions-bar',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './batch-actions-bar.component.html',
  styleUrl: './batch-actions-bar.component.scss'
})
export class BatchActionsBarComponent {
  @Input() selectedCount = 0;
  @Input() canDelete = false;

  @Output() deleteFromICloud = new EventEmitter<void>();
  @Output() deleteFromIPhone = new EventEmitter<void>();
  @Output() clearSelection = new EventEmitter<void>();
}
