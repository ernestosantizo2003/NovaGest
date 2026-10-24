import { defineConfig, devices } from '@playwright/test'

/**
 * Tarjeta 41: recorrido de humo de punta a punta (navegador real + API real + base real).
 * Necesita la API levantada en http://localhost:8080 con el perfil dev (docker compose up --build), porque usa los
 * usuarios demo de la tarjeta 24. El frontend lo levanta Playwright con `npm run dev` (o reusa el que ya corre).
 * No es parte de `npm run verify`: se corre con `npm run e2e` y en la CI con .github/workflows/e2e.yml.
 */
export default defineConfig({
  testDir: './e2e',
  testMatch: '**/*.e2e.ts',
  // El recorrido es una sola historia en orden: no se reparte entre trabajadores
  fullyParallel: false,
  workers: 1,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  timeout: 120_000,
  expect: { timeout: 15_000 },
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: 'http://localhost:5173',
    locale: 'es-GT',
    timezoneId: 'America/Guatemala',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:5173',
    reuseExistingServer: !process.env.CI,
    timeout: 60_000,
  },
})
