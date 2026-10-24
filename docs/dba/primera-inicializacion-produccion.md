# Primera inicializacion de tramites_produccion

Preparacion para revision; **no ejecutada en daniel1**. No repite el cierre de staging/#87.

Rodney confirma base/esquema vacios, owner `tramites_prod_owner`, migrador con CREATE/USAGE/SET y app con USAGE, sin CREATE ni SET al owner. SSL ON; las dos reglas productivas SCRAM todavia son `host`. Pasarlas a `hostssl`, comprobar orden/carga y conexiones nuevas requiere ventana y autorizacion propias.

## Problema reproducido y solucion

Los nueve SQL del release (V1,V2,V3,V4,V5,V6,V8,V9,V10) no contienen SET ROLE, ALTER OWNER ni GRANT de roles PostgreSQL. Los INSERT en `rol`/`permiso` son permisos de negocio, no ACL de PostgreSQL. V1 registra la linea base; V2 crea cinco tablas; V3 una; V4 una; V5 dos; V6 dos y la funcion/los triggers inmutables; V8, V9 y V10 una cada una. Resultado: 14 tablas de negocio y el historial creado por Flyway, 12 secuencias de identidad.

Todos los nombres de tablas/funcion estan sin calificar. El esquema se debe seleccionar explicitamente. El propietario de los objetos es el `current_user` que ejecuta cada CREATE, no necesariamente el propietario del esquema. La membresia INHERIT/SET no ejecuta SET ROLE automaticamente. Los defaults son del creador efectivo y no se heredan del owner del esquema.

El codigo base solo establece usuario/clave separados desde Compose. `SPRING_FLYWAY_INIT_SQLS` puede existir en el `app.env` privado; este trabajo no lo lee ni certifica. La prueba aislada reproduce que omitir SET deja los objetos en el migrador y omitir los GRANT impide las lecturas de la app (42501).

El perfil optativo `dba-produccion`, **junto con `prod`**, fija `SET ROLE tramites_prod_owner` en las conexiones de Flyway, esquema `tramites`, sin crear esquemas ni baseline automatico. Activa dos callbacks externos a los SQL versionados:

- `beforeMigrate`: exige base/login/rol/esquema correctos y aislamiento de app. Rechaza defaults amplios que expondrian el historial; no modifica defaults silenciosamente.
- `afterMigrate`: verifica propietarios y ausencia de acceso al historial, tambien por columnas/PUBLIC/roles alcanzables con SET. Concede CRUD explicito sobre once tablas de negocio, SELECT sobre `permiso`, SELECT/INSERT sobre bitacora e historial de tramites, y USAGE/SELECT sobre las doce secuencias. No concede DDL, TRUNCATE ni SETVAL. No aplica permisos a tablas futuras por inferencia.

Esto no edita V9/V10, no agrega V11 ni cambia los perfiles de staging/test existentes. Flyway puede crear el historial antes de beforeMigrate, y un fallo de afterMigrate puede ocurrir **despues** de confirmar migraciones. Un arranque fallido no implica base vacia ni rollback de todas las migraciones: detenerse e inventariar, sin repair/clean/restauracion ni GRANT amplios automaticos.

## Activacion que debe revisar Adrian antes de la ventana

Tras aprobar e integrar la PR y construir/probar una imagen por commit/tag/digest, revisar **solo el entorno production**:

```properties
# Una sola entrada en app.env, incorporada mediante recreacion autorizada por Adrian.
# Compose ya fija SPRING_PROFILES_ACTIVE=prod.
SPRING_PROFILES_INCLUDE=dba-produccion
```

No ejecutar esta edicion/recreacion ahora. Contrastar que `DB_NAME=tramites_produccion`, `DB_USER=tramites_prod_app`, `FLYWAY_USER=tramites_prod_migrador`; verificar ausencia de overrides contradictorios de `SPRING_FLYWAY_INIT_SQLS/LOCATIONS/SCHEMAS/DEFAULT_SCHEMA`, ddl-auto y URL. No imprimir archivos de entorno, claves o URLs completas. La URL de la app debe seleccionar `tramites`, exigir TLS y verificar la identidad del servidor conforme al certificado aprobado. El init SQL afecta Flyway, no al pool de la app.

Conservar la configuracion en futuros arranques; los callbacks son idempotentes en ausencia de cambios de seguridad. La clase `ProduccionInicializacionTests` verifica primera migracion real y arranque completo con `ddl-auto=validate`, health, registro y login con los roles separados. No es validacion del digest aun no publicado.

## Respaldo productivo del controlador

El controlador corregido mantiene el orden pull → respaldo → compose up. En production exige el usuario de Flyway para pg_dump; elimina el fallback a la app y ejecuta `--role=tramites_prod_owner` (override explicito FLYWAY_OWNER), sin depender de INHERIT. Crea/restringe `backups` a 700 y exige propietario operador, rechaza enlaces; `mktemp` crea el temporal a 600 desde el inicio aun con umask022. Publica dump y SHA256 a 600 despues de leer TOC y todo el archivo con pg_restore, sin ejecutar SQL, y comprueba la huella publicada. Los errores detienen el despliegue. Un temporal privado puede quedar tras una interrupcion abrupta: no usarlo como respaldo acreditado.

Ruta: `/opt/tramites/production/backups/pre-deploy-<UTC>-<tag>-<aleatorio>.dump`, mas `.sha256`. Retencion predeterminada: diez dumps y sus huellas; admite override entero positivo (maximo cuatro cifras), que Adrian debe acreditar como diez en esta ventana. Solo poda despues de verificar/publicar el nuevo dump; no es un respaldo diario ni incluye roles globales o comprobantes.

Antes del primer despliegue se respalda la base vacia: ese dump acredita el punto previo y no sustituye el primer respaldo posterior a inicializar, ni la politica diaria de Marlon. `pg_restore --list` o generar SQL no prueban una restauracion satisfactoria. Se necesita restauracion **autorizada**, privada y aislada del dump productivo, sin puertos publicos, con roles compatibles precreados, `pg_restore --exit-on-error` y comparacion de esquema/owners/ACL/secuencias/historial/datos. No usar `--no-owner/--no-acl` para afirmar equivalencia de esos controles.

Las pruebas de esta PR restauran datos sinteticos en una segunda base de un cluster nuevo PostgreSQL18.6 y comparan datos, owner/ACL y secuencia; no restauran ningun dump real. Para el dump productivo, Adrian/DBA deben registrar ruta, propietario/modos desde temporal, fecha, pg_dump/server version compatibles, bytes, SHA256 comprobada, resultado de restauracion y ubicacion protegida de la evidencia. Los dumps pueden contener datos sensibles: no adjuntarlos a GitHub. Retencion diez debe probarse sin fabricar/eliminar respaldos reales para el ensayo.

## Criterios de la ventana y separacion de pendientes

**Antes de autorizar el primer despliegue:** aprobacion/revision DBA y Adrian de estas correcciones o configuracion equivalente probada; candidata nueva sin Pebble identificada/escaneada; HBA hostssl/SCRAM y TLS nuevo correcto (rechazar sin TLS); decision del custodio sobre credenciales definitivas; respaldo previo protegido y plan de recuperacion; ventana Julio/Ernesto y Adrian. Si hay tablas/historial inesperados al empezar, detener la inicializacion vacia y evaluar el estado real.

**Durante la ejecucion autorizada:** Flyway con migrador/SET owner aplica exactamente nueve versiones, termina en10 y valida checksums; Hibernate validate termina, propietarios/ACL/SET son correctos y app obtiene42501 en historial. Verificar health/login y negocio sin exponer datos. Es un primer despliegue: puede no haber imagen anterior ni rollback automatico util. Un rollback de imagen no revierte SQL/ACL/datos; no restaurar automaticamente.

**Seguimiento separado:** #96 (bordes numericos/respuesta y proveedor) y posible V11 (coherencia adicional V9) conservan su revision/aceptacion funcional propia; no son incompatibilidades de primera creacion demostradas. No reejecutar #87 ni rotar claves por esta PR. Windows/replicacion no es condicion para inicializar produccion. La autorizacion DBA condicionada publicada no activa Deploy ni autoriza cambios del servidor por si sola.

## Pruebas reproducibles solo en laboratorio

```text
python docs/dba/tests/probar_inicializacion_produccion.py --pg-bin <binarios-PG18-locales>
python docs/dba/tests/probar_respaldo_produccion.py --pg-bin <binarios-PG18-locales> [--bash <bash>]
```

La primera prueba crea un cluster temporal propio, loopback y puerto distinto de5432; verifica data_directory y ausencia inicial de los roles antes de DDL. La alternativa normal JUnit usa Testcontainers aislado. En Windows, los modos POSIX no se certifican con NTFS/Git Bash; `--solo-mocks` del segundo script permite probar 600/700 bajo Linux sin iniciar PostgreSQL. CI DBA ejecuta la suite completa de respaldos bajo Linux con PG18 efimero, mas las guardias anteriores de staging, sin conectarse a servidores compartidos.
