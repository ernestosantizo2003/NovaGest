import type { Perfil } from '../api/tipos'

interface Capacidad {
  permiso: string
  /** Si se indica, solo aplica según el alcance del rol (true: ve todo, false: solo lo propio). */
  alcanceTotal?: boolean
  texto: string
  /** Pantalla a la que lleva esta acción: el panel la muestra como un acceso directo. */
  ruta: string
}

/**
 * Qué puede hacer una persona, en palabras, según los permisos que tiene. Se usa en el panel para que cada rol vea
 * de un vistazo lo suyo. Es solo informativo: lo que realmente se puede hacer lo decide la API.
 */
const CAPACIDADES: Capacidad[] = [
  { permiso: 'tramite:crear', texto: 'Crear y enviar trámites', ruta: '/tramites/nuevo' },
  {
    permiso: 'tramite:leer',
    alcanceTotal: false,
    texto: 'Consultar y dar seguimiento a tus trámites',
    ruta: '/tramites',
  },
  {
    permiso: 'tramite:leer',
    alcanceTotal: true,
    texto: 'Consultar los trámites de todas las personas',
    ruta: '/tramites',
  },
  {
    permiso: 'tramite:editar',
    alcanceTotal: true,
    texto: 'Revisar trámites: tomarlos, observarlos, aprobarlos o rechazarlos',
    ruta: '/bandeja',
  },
  { permiso: 'pago:crear', texto: 'Subir comprobantes de pago', ruta: '/tramites' },
  { permiso: 'pago:editar', texto: 'Validar o rechazar pagos', ruta: '/pagos' },
  { permiso: 'ingreso:leer', texto: 'Registrar y consultar tus ingresos', ruta: '/ingresos' },
  { permiso: 'tipo_tramite:crear', texto: 'Administrar los tipos de trámite', ruta: '/tipos' },
  { permiso: 'usuario:crear', texto: 'Administrar usuarios y roles', ruta: '/usuarios' },
  { permiso: 'bitacora:leer', texto: 'Consultar la bitácora de auditoría', ruta: '/bitacora' },
]

/** Lo que la persona puede hacer, con la pantalla a la que lleva cada acción. */
export function accesosDe(perfil: Perfil): { texto: string; ruta: string }[] {
  return CAPACIDADES.filter(
    (c) =>
      perfil.permisos.includes(c.permiso) &&
      (c.alcanceTotal === undefined || c.alcanceTotal === perfil.alcanceTotal),
  ).map((c) => ({ texto: c.texto, ruta: c.ruta }))
}

export function capacidadesDe(perfil: Perfil): string[] {
  return accesosDe(perfil).map((a) => a.texto)
}
