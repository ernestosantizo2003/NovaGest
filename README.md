# NovaGest

Sistema de tramites ciudadanos (curso de Auditoria de Sistemas). API en Spring Boot 4 (Java 21) con PostgreSQL, autenticacion JWT y control de acceso por rol.

Equipo de desarrollo: Ernesto (encargado), Brandom, Frank.
Documentacion y tablero: Notion, pagina NovaGest.

## Requisitos
- Java 21
- Docker Desktop encendido (pruebas con Testcontainers y base local)
- No hace falta instalar Maven: usar el wrapper `mvnw`

## Comandos
```
./mvnw clean verify      # compila y corre pruebas (Docker encendido)
./mvnw -DskipTests package   # solo compilar
```
En Windows (PowerShell): `.\mvnw.cmd clean verify`

Levantar app y base juntas (requiere `.env` completo):
```
docker compose up --build    # app en http://localhost:8080, base en el puerto DB_PORT
docker compose down          # detiene; agregar -v borra tambien los datos de la base
```

## Configuracion
Copiar `.env.example` a `.env` y completar. Los secretos entran solo por variables de entorno; `.env` esta en `.gitignore`.

## Flujo de trabajo
- `main` es la version entregable y `develop` es integracion. Ninguna se toca directo.
- Una rama por tarjeta: `feature/08-login`, `fix/27-bloqueo-cuenta`.
- Commits cortos, en imperativo y con el numero de tarjeta: `08 Agregar login con JWT`.
- Todo entra a `develop` por pull request, aprobado por otra persona (rotacion: Ernesto revisa a Brandom, Brandom a Frank, Frank a Ernesto).
- Cada modulo vive en su carpeta (`controller`, `service`, `repository`, `dto`, `entity`) bajo `com.proyecto.tramites`.

## Reglas del codigo
- El controlador solo recibe, valida y responde; la logica va en el servicio.
- Las entidades JPA nunca salen por la API: se responden DTO.
- `spring.jpa.hibernate.ddl-auto=validate`: el esquema cambia solo con migraciones de Flyway.
- Ningun token ni contrasena en logs ni en URLs.
