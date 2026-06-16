import '@angular/compiler';
import '@analogjs/vitest-angular/setup-zone';
import { getTestBed } from '@angular/core/testing';
import { setupTestBed } from '@analogjs/vitest-angular/setup-testbed';

console.error('[test-setup] running, getTestBed=', typeof getTestBed);
try {
  setupTestBed({ zoneless: false });
  console.error('[test-setup] setupTestBed OK, platform=', !!(getTestBed() as any).platform);
} catch (e) {
  console.error('[test-setup] setupTestBed THREW', e);
}
