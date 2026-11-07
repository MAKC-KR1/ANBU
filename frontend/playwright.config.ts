import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: 'e2e',
  workers: 1,
  use: {
    baseURL: 'http://127.0.0.1:15173',
    browserName: 'chromium',
    channel: process.env.PLAYWRIGHT_CHANNEL || undefined,
    viewport: { width: 1440, height: 900 },
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  webServer: [
    {
      command: "java -jar ../build/libs/anbu-0.1.0.jar --spring.profiles.active=ansan-map,void-preview --server.port=18080 '--spring.datasource.url=jdbc:h2:mem:anbu-e2e;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH'",
      url: 'http://127.0.0.1:18080/api/v1/dongs',
      env: { ANBU_VOID_PREVIEW_PASSWORD: '1111', ANBU_CORS_ORIGINS: 'http://127.0.0.1:15173' },
      timeout: 60_000,
    },
    {
      command: 'npm run dev -- --port 15173',
      url: 'http://127.0.0.1:15173',
      env: { VITE_VOID_PREVIEW: 'true', ANBU_API_PROXY: 'http://127.0.0.1:18080' },
    },
  ],
})
