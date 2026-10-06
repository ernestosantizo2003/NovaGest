# Guia P1: estudio de Spring Security y JWT

Tarjeta P1 del tablero. La hacen los tres juntos, en un dia (unas 5 a 6 horas en total). Nadie del equipo tiene experiencia previa en Spring Security, y de esto dependen las tarjetas 7 a 13.

**Meta:** que los tres entiendan como se autentica una peticion y como se aplican los permisos, usando un ejemplo pequeno que ya funciona (`docs/ejemplo-jwt`). No es parte del sistema NovaGest: es material de estudio.

## Plan del dia

| Bloque | Duracion | Que hacer |
| --- | --- | --- |
| 1. Conceptos | 1 h | Leer la seccion "Conceptos" de esta guia y las preguntas de autoevaluacion. Cada quien por su cuenta. |
| 2. Ejemplo | 1.5 h | Los tres juntos: correr las pruebas, arrancar el ejemplo, leer cada archivo en el orden indicado. |
| 3. Ejercicios | 2 h | Parejas rotando: hacer los ejercicios de la lista. Quien no escribe, revisa. |
| 4. Cierre | 30 min | Responder en voz alta las preguntas de autoevaluacion. Si alguien duda, repetir ese tema. |

## Conceptos que hay que entender

1. **Autenticacion y autorizacion.** Autenticar es saber quien eres (login). Autorizar es decidir que puedes hacer (permisos). Son pasos distintos.
2. **401 y 403.** 401 = no se pudo identificar (sin token, token vencido o alterado). 403 = te conozco, pero no tienes permiso.
3. **Sin estado (STATELESS).** El servidor no guarda sesion en memoria. Cada peticion trae su token y se valida de nuevo.
4. **JWT.** Un token firmado con tres partes: cabecera, contenido (claims: usuario, emision, vencimiento, id unico) y firma. Si alguien cambia una letra, la firma ya no coincide y se rechaza. El contenido se puede leer, por eso nunca va ahi una contrasena.
5. **Cadena de filtros (SecurityFilterChain).** Spring Security es una fila de filtros que se ejecutan antes del controlador. Uno de ellos lee el `Authorization: Bearer ...`, valida el token y deja pasar o corta.
6. **Authority (permiso).** Un texto como `tramite:leer`. El codigo pregunta "tiene este permiso?", nunca "es administrador?".
7. **@PreAuthorize.** Anotacion en el metodo del controlador que exige un permiso: `@PreAuthorize("hasAuthority('tramite:leer')")`.
8. **Los permisos no viajan en el token.** El token solo dice quien es el usuario. En cada peticion el servidor busca sus permisos actuales. Asi, si le quitan un rol, el cambio aplica de inmediato.

## Preguntas de autoevaluacion

- Que diferencia hay entre un 401 y un 403? Da un ejemplo de cada uno.
- Por que la API es STATELESS y que ventaja tiene?
- Que pasa si alguien modifica el contenido del token? Por que?
- Por que los permisos no se guardan dentro del token?
- Que pasa si se filtra la clave de firma (`JWT_SECRET`)?
- Donde se decide que una ruta es publica y cual exige token?

## Como correr el ejemplo

Requisito: Java 21. No hace falta Docker porque el ejemplo no usa base de datos. Desde la raiz del repositorio:

```
cd docs\ejemplo-jwt
.\mvnw.cmd test
```

Deben pasar 6 pruebas. Para arrancarlo a mano (PowerShell):

```
cd docs\ejemplo-jwt
$env:JWT_SECRET = "una-clave-de-al-menos-32-caracteres-para-pruebas"
$env:SERVER_PORT = "8099"
.\mvnw.cmd spring-boot:run
```

En otra ventana:

```
$r = Invoke-RestMethod -Method Post -Uri http://localhost:8099/api/auth/login -ContentType "application/json" -Body '{"correo":"ciudadano@demo.com","clave":"clave123"}'
$h = @{ Authorization = "Bearer $($r.accessToken)" }
Invoke-RestMethod -Uri http://localhost:8099/api/yo -Headers $h
Invoke-RestMethod -Uri http://localhost:8099/api/usuarios-eliminar -Headers $h
```

La ultima llamada debe responder 403 (el ciudadano no tiene `usuario:eliminar`). Usuarios de juguete: `ciudadano@demo.com` y `admin@demo.com`, ambos con clave `clave123`. Son solo para este ejemplo.

## Orden para leer el ejemplo

Los archivos estan en `docs/ejemplo-jwt/src/main/java/com/proyecto/spike/`.

1. `UsersConfig`: los usuarios y sus permisos (en NovaGest vendran de PostgreSQL, tarjeta 7).
2. `AuthController`: el login. Valida correo y clave, y emite un JWT que vence en 15 minutos con un identificador unico.
3. `SecurityConfig`: el corazon. Define la clave, como se firma y valida el token, que rutas son publicas, que la sesion es STATELESS, y como se cargan los permisos desde el servidor en cada peticion.
4. `DemoController`: tres endpoints para probar: uno solo con token, uno con `tramite:leer` y uno con `usuario:eliminar`.
5. `src/test/.../SeguridadTests`: las 6 pruebas. Leer sobre todo `tokenAlteradoEs401` y `conTokenPeroSinPermisoEs403`: son el tipo de prueba que pide el plan.

## Ejercicios

1. Cambiar la vigencia del token a 1 minuto, hacer login y comprobar que a los 61 segundos la API responde 401.
2. Agregar un tercer usuario `gestor@demo.com` con los permisos `tramite:leer` y `tramite:editar`, y un endpoint que exija `tramite:editar`. Escribir su prueba de permiso denegado y de permiso concedido.
3. Quitarle un permiso a un usuario despues de que ya hizo login (en el codigo, reiniciando) y comprobar que el token anterior ya no sirve para ese permiso. Explicar por que.
4. Arrancar con un `JWT_SECRET` de menos de 32 caracteres y ver el error. Explicar por que se exige ese minimo.
5. Poner a proposito una ruta nueva sin permiso en `SecurityConfig` y ver que "denegar por defecto" la protege.

## Decision tecnica que hay que aprobar

El plan dice "JWT firmado, validado por Spring Security". El ejemplo lo cumple con las clases de Spring Security (`NimbusJwtEncoder` y `NimbusJwtDecoder`), sin agregar una libreria externa de JWT. Razon: el proyecto usa Spring Boot 4, que es muy nuevo y trae cambios internos (por ejemplo en la libreria de JSON); asi evitamos incompatibilidades. El ejemplo se probo con Spring Boot 4.1.1.

Hay que confirmar entre los tres que usaremos este enfoque para las tarjetas 8 y 9.

## Que NO cubre el ejemplo (viene en las tarjetas)

| Tema | Tarjeta |
| --- | --- |
| Usuarios, roles y permisos en base de datos | 7 |
| Registro y token de renovacion, bloqueo por intentos, logout | 8, 10 |
| Respuesta de error en formato Problem Details para 401 y 403 | 4, 9 |
| Permisos por rol desde la base y propiedad del dato (404 ante registro ajeno) | 11 |
| Administracion de usuarios y roles | 12 |
| Test que recorre la matriz rol por endpoint | 13 |
| Bitacora de quien hizo que | 23 |

## Hecho cuando

- [ ] Los tres corrieron las 6 pruebas y arrancaron el ejemplo.
- [ ] Los tres respondieron las preguntas de autoevaluacion sin ayuda.
- [ ] Se hicieron al menos los ejercicios 1, 2 y 3.
- [ ] Se confirmo el enfoque tecnico de la seccion anterior.
- [ ] La tarjeta P1 se paso a Hecho en el tablero.

## Documentacion oficial (para profundizar)

- Spring Security, referencia: https://docs.spring.io/spring-security/reference/ (secciones de arquitectura de Servlet, autorizacion con `@PreAuthorize` y "OAuth 2.0 Resource Server" con JWT).
- Spring Boot, referencia: https://docs.spring.io/spring-boot/
