import { defineConfig } from 'vitest/config';
import angular from '@analogjs/vite-plugin-angular';

export default defineConfig((env) => {
  const config ={
  plugins: [],
  resolve: {
    dedupe: [
      '@angular/core',
      '@angular/common',
      '@angular/compiler',
      '@angular/platform-browser',
      '@angular/platform-browser-dynamic',
    ],
  },
  // Inline the analog TestBed setup and Angular's `testing` fesm bundles into the
  // same module graph as the specs. Without this they get externalized into a
  // separate `@angular/core/testing` instance, so `setupTestBed()`'s
  // `initTestEnvironment()` never reaches the TestBed the specs use -> tests fail
  // with "Need to call TestBed.initTestEnvironment() first".
  ssr: {
    noExternal: [
      '@analogjs/vitest-angular/setup-testbed',
      /fesm2022(.*?)testing/,
      /fesm2015/,
    ],
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['src/test-setup.ts'],
    include: ['src/**/*.spec.ts'],
    env: {
      API_URL: 'http://localhost:8080',
    },
  },
  }

  return config;
});
