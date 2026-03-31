import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="container">
      <div class="section-header">
        <h2>Dashboard</h2>
      </div>
      <div class="card">
        <p>Dashboard - Coming Soon</p>
      </div>
    </div>
  `
})
export class DashboardComponent {
  title = 'Dashboard';
}
