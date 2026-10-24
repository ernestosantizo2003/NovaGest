import { Link } from 'react-router-dom'
import { useTitulo } from '../componentes/useTitulo'
import Insignia from '../componentes/Insignia'
import { accesosDe } from '../sesion/capacidades'
import { useSesion } from '../sesion/contexto'

/** Pantalla de entrada tras iniciar sesión: cada acción que el rol permite es un acceso directo a su pantalla. */
export default function Panel() {
  const { perfil } = useSesion()
  useTitulo('Mi panel')
  if (!perfil) return null
  const accesos = accesosDe(perfil)

  return (
    <>
      <div className="encabezado-pagina">
        <h1>Hola, {perfil.nombre}</h1>
        <p>
          Entraste como <Insignia>{perfil.roles.join(', ')}</Insignia>{' '}
          {perfil.alcanceTotal
            ? 'y ves los trámites de todas las personas.'
            : 'y ves solo tus trámites.'}
        </p>
      </div>
      <section className="tarjeta" aria-labelledby="titulo-capacidades">
        <h2 id="titulo-capacidades">Lo que puedes hacer</h2>
        {accesos.length > 0 ? (
          <ul className="lista-capacidades">
            {accesos.map(({ texto, ruta }) => (
              <li key={texto}>
                <Link to={ruta}>{texto}</Link>
              </li>
            ))}
          </ul>
        ) : (
          <p>Tu rol todavía no tiene acciones habilitadas. Consulta con un administrador.</p>
        )}
      </section>
    </>
  )
}
