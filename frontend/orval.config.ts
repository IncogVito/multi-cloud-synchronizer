import { defineConfig } from 'orval';

export default defineConfig({
  cloudSync: {
    input: {
      target: '../openapi/openapi.yml',
    },
    output: {
      target: './src/app/core/api/generated/index.ts',
      schemas: './src/app/core/api/generated/model',
      client: 'angular',
      mode: 'tags-split',
      baseUrl: '/api',
      prettier: true,
    },
  },
});
