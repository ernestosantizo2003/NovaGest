import { expect, test } from '@playwright/test'
import {
  accionDelTramite,
  cerrarSesion,
  claveAlAzar,
  claveDemo,
  comprobantePdf,
  correoUnico,
  iniciarSesion,
} from './apoyo.ts'

/**
 * Tarjeta 41: recorrido de humo de punta a punta con el sistema completo. Una persona se registra, crea y envía un
 * trámite; el gestor demo lo revisa y lo aprueba; la persona sube el comprobante; el gestor valida el pago desde
 * "Pagos pendientes" (tarjeta 36) y el trámite queda finalizado. Se usa el tipo demo "Constancia de residencia"
 * (tarjeta 24), que cobra Q25 y no pide datos. Selectores por rol y etiqueta, como en las pruebas de Vitest.
 */
test('recorrido completo: registro, trámite, revisión, pago y finalizado', async ({ page }) => {
  const correo = correoUnico()
  const clave = claveAlAzar()
  const gestor = { correo: 'gestor@novagest.test', clave: claveDemo() }
  let tramiteId = ''

  await test.step('la persona se registra e inicia sesión', async () => {
    await page.goto('/registro')
    await page.getByLabel('Nombre completo').fill('Persona Recorrido E2E')
    await page.getByLabel('Correo').fill(correo)
    await page.getByLabel('Contraseña').fill(clave)
    await page.getByRole('button', { name: 'Crear cuenta' }).click()
    await expect(page.getByText('Tu cuenta fue creada. Ya puedes iniciar sesión.')).toBeVisible()
    await iniciarSesion(page, correo, clave)
  })

  await test.step('crea el trámite como borrador y lo envía', async () => {
    await page.goto('/tramites/nuevo')
    await page.getByLabel('Tipo de trámite').selectOption({ label: 'Constancia de residencia' })
    await page.getByRole('button', { name: 'Guardar borrador' }).click()
    await expect(page).toHaveURL(/\/tramites\/\d+$/)
    tramiteId = /\/tramites\/(\d+)$/.exec(page.url())![1]
    await expect(page.getByRole('heading', { name: `Trámite #${tramiteId}` })).toContainText(
      'Borrador',
    )
    await accionDelTramite(page, 'Enviar trámite', /fue enviado/)
    await cerrarSesion(page)
  })

  await test.step('el gestor lo toma, lo revisa y lo aprueba', async () => {
    await iniciarSesion(page, gestor.correo, gestor.clave)
    await page.goto(`/tramites/${tramiteId}`)
    await accionDelTramite(page, 'Tomar para revisión', /quedó en revisión/)
    await accionDelTramite(page, 'Aprobar y pasar a pago', /pendiente de pago/)
    await expect(page.getByRole('heading', { name: `Trámite #${tramiteId}` })).toContainText(
      'Por pagar',
    )
    await cerrarSesion(page)
  })

  await test.step('la persona sube el comprobante de pago', async () => {
    await iniciarSesion(page, correo, clave)
    await page.goto(`/tramites/${tramiteId}`)
    await page.getByLabel('Comprobante de pago').setInputFiles(comprobantePdf())
    await page.getByRole('button', { name: 'Subir comprobante' }).click()
    await expect(page.getByText(/Recibimos tu comprobante/)).toBeVisible()
    await cerrarSesion(page)
  })

  await test.step('el gestor valida el pago desde "Pagos pendientes"', async () => {
    await iniciarSesion(page, gestor.correo, gestor.clave)
    await page.goto('/pagos')
    const fila = page.getByRole('row').filter({
      has: page.getByRole('link', { name: `Ver trámite #${tramiteId}` }),
    })
    await fila.getByRole('button', { name: /^Validar el pago/ }).click()
    await fila.getByRole('button', { name: 'Confirmar validación' }).click()
    // Al guardarse la decisión la lista se recarga y el pago resuelto deja de estar pendiente
    await expect(fila).toHaveCount(0)
    await cerrarSesion(page)
  })

  await test.step('la persona ve su trámite finalizado', async () => {
    await iniciarSesion(page, correo, clave)
    await page.goto(`/tramites/${tramiteId}`)
    await expect(page.getByRole('heading', { name: `Trámite #${tramiteId}` })).toContainText(
      'Finalizado',
    )
  })
})
