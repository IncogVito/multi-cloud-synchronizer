import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-photos',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="container">
      <div class="section-header">
        <h2>Photos</h2>
      </div>
      <div class="card">
        <p>Photos - Coming Soon</p>
      </div>
    </div>
  `
})
export class PhotosComponent {}
