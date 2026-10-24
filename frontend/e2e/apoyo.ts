import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { expect, type Page } from '@playwright/test'

/**
 * Clave de los usuarios demo (DEMO_PASSWORD). En la CI llega como variable de entorno, generada al azar en el job;
 * en una máquina local se lee del .env de la raíz si no está en el entorno. Nunca se escribe en el código.
 */
export function claveDemo(): string {
  const delEntorno = process.env.DEMO_PASSWORD?.trim()
  if (delEntorno) return delEntorno
  try {
    const ruta = fileURLToPath(new URL('../../.env', import.meta.url))
    const linea = readFileSync(ruta, 'utf8')
      .split(/\r?\n/)
      .find((l) => l.startsWith('DEMO_PASSWORD='))
    const valor = linea?.slice('DEMO_PASSWORD='.length).trim()
    if (valor) return valor
  } catch {
    // Sin .env: se informa abajo
  }
  throw new Error(
    'Falta DEMO_PASSWORD: ponla en el entorno o en el .env de la raíz del repositorio.',
  )
}

/** Correo único por corrida, en el dominio de pruebas que nunca recibe correo real. */
export function correoUnico(): string {
  return `e2e.${Date.now()}.${Math.floor(Math.random() * 1e6)}@novagest.test`
}

/** Una clave al azar para la cuenta creada en el recorrido (8 a 72 caracteres, como pide la API). */
export function claveAlAzar(): string {
  return `E2e-${crypto.randomUUID()}`
}

/**
 * Un PDF mínimo válido. La API reconoce el formato por los primeros bytes (%PDF), no por el nombre ni por el tipo
 * que diga el navegador, así que se arma uno de verdad en memoria.
 */
export function comprobantePdf(): { name: string; mimeType: string; buffer: Buffer } {
  const pdf = [
    '%PDF-1.4',
    '1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj',
    '2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >> endobj',
    '3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] >> endobj',
    'trailer << /Root 1 0 R >>',
    '%%EOF',
  ].join('\n')
  return { name: 'comprobante-e2e.pdf', mimeType: 'application/pdf', buffer: Buffer.from(pdf) }
}

export async function iniciarSesion(page: Page, correo: string, clave: string): Promise<void> {
  await page.goto('/ingresar')
  await page.getByLabel('Correo').fill(correo)
  await page.getByLabel('Contraseña').fill(clave)
  await page.getByRole('button', { name: 'Ingresar' }).click()
  // No se exige /panel: si se llegó al login desde otra pantalla, la app vuelve a esa pantalla al entrar
  await expect(page.getByRole('button', { name: 'Cerrar sesión' })).toBeVisible()
}

export async function cerrarSesion(page: Page): Promise<void> {
  await page.getByRole('button', { name: 'Cerrar sesión' }).click()
  await expect(page.getByRole('link', { name: 'Iniciar sesión' }).first()).toBeVisible()
}

/** Ejecuta una acción de la máquina de estados desde el detalle y espera su mensaje de resultado. */
export async function accionDelTramite(
  page: Page,
  boton: string,
  resultado: RegExp,
): Promise<void> {
  await page.getByRole('button', { name: boton }).click()
  await expect(page.getByRole('status').filter({ hasText: resultado })).toBeVisible()
}
