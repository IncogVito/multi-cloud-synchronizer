import { Component, input, output } from '@angular/core';

@Component({
  selector: 'app-batch-actions-bar',
  standalone: true,
  imports: [],
  templateUrl: './batch-actions-bar.component.html',
  styleUrl: './batch-actions-bar.component.scss'
})
export class BatchActionsBarComponent {
  selectedCount = input(0);
  canDelete = input(false);

  deleteFromICloud = output<void>();
  deleteFromIPhone = output<void>();
  clearSelection = output<void>();
}
