-- Tarjeta 7: usuarios, roles y permisos (borrador para revision del equipo de DBA, tarjeta P2).
-- Un permiso es un recurso mas una accion (CRUD). Los roles son solo conjuntos de permisos,
-- asi crear o ajustar un rol no exige tocar codigo. Los datos iniciales salen de la matriz del plan.

CREATE TABLE usuario (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    correo            VARCHAR(254) NOT NULL,
    nombre            VARCHAR(120) NOT NULL,
    hash_contrasena   VARCHAR(100) NOT NULL,
    activo            BOOLEAN      NOT NULL DEFAULT TRUE,
    intentos_fallidos INTEGER      NOT NULL DEFAULT 0,
    bloqueado_hasta   TIMESTAMPTZ,
    creado_en         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    actualizado_en    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    eliminado_en      TIMESTAMPTZ
);

-- Un correo solo puede estar en uso por un usuario no eliminado, sin importar mayusculas.
CREATE UNIQUE INDEX uq_usuario_correo_activo ON usuario (lower(correo)) WHERE eliminado_en IS NULL;

CREATE TABLE rol (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nombre         VARCHAR(50)  NOT NULL UNIQUE,
    descripcion    VARCHAR(255),
    creado_en      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    actualizado_en TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE permiso (
    id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    recurso VARCHAR(50) NOT NULL,
    accion  VARCHAR(20) NOT NULL,
    CONSTRAINT ck_permiso_accion CHECK (accion IN ('leer', 'crear', 'editar', 'eliminar')),
    CONSTRAINT uq_permiso_recurso_accion UNIQUE (recurso, accion)
);

CREATE TABLE usuario_rol (
    usuario_id BIGINT NOT NULL REFERENCES usuario (id),
    rol_id     BIGINT NOT NULL REFERENCES rol (id),
    PRIMARY KEY (usuario_id, rol_id)
);

CREATE TABLE rol_permiso (
    rol_id     BIGINT NOT NULL REFERENCES rol (id),
    permiso_id BIGINT NOT NULL REFERENCES permiso (id),
    PRIMARY KEY (rol_id, permiso_id)
);

CREATE INDEX ix_usuario_rol_rol ON usuario_rol (rol_id);
CREATE INDEX ix_rol_permiso_permiso ON rol_permiso (permiso_id);

-- Datos iniciales: 25 permisos (6 recursos con CRUD completo mas bitacora:leer).
INSERT INTO permiso (recurso, accion) VALUES
    ('tramite', 'leer'), ('tramite', 'crear'), ('tramite', 'editar'), ('tramite', 'eliminar'),
    ('tipo_tramite', 'leer'), ('tipo_tramite', 'crear'), ('tipo_tramite', 'editar'), ('tipo_tramite', 'eliminar'),
    ('pago', 'leer'), ('pago', 'crear'), ('pago', 'editar'), ('pago', 'eliminar'),
    ('ingreso', 'leer'), ('ingreso', 'crear'), ('ingreso', 'editar'), ('ingreso', 'eliminar'),
    ('usuario', 'leer'), ('usuario', 'crear'), ('usuario', 'editar'), ('usuario', 'eliminar'),
    ('rol', 'leer'), ('rol', 'crear'), ('rol', 'editar'), ('rol', 'eliminar'),
    ('bitacora', 'leer');

INSERT INTO rol (nombre, descripcion) VALUES
    ('CIUDADANO', 'Registra y da seguimiento a sus propios tramites, pagos e ingresos'),
    ('GESTOR', 'Revisa tramites, cambia su estado y valida pagos'),
    ('AUDITOR', 'Consulta tramites, pagos, usuarios, roles y bitacora; solo lectura'),
    ('ADMINISTRADOR', 'Administra usuarios, roles, tipos de tramite y catalogos');

-- Matriz del plan. Notas:
--  * Los ingresos son dato personal: solo el CIUDADANO tiene permisos de ingreso, ni siquiera el administrador.
--  * El CIUDADANO edita su propio perfil por /api/usuarios/yo (solo autenticado), no con usuario:editar,
--    que daria acceso a editar a otros usuarios.
--  * tramite:editar del CIUDADANO se limita en el servicio a sus tramites en BORRADOR u OBSERVADO.
--  * La bitacora la lee el AUDITOR y el ADMINISTRADOR; nadie la edita ni la borra (por eso solo existe leer).
INSERT INTO rol_permiso (rol_id, permiso_id)
SELECT r.id, p.id
FROM (VALUES
    ('CIUDADANO', 'tramite', 'leer'), ('CIUDADANO', 'tramite', 'crear'), ('CIUDADANO', 'tramite', 'editar'),
    ('CIUDADANO', 'tipo_tramite', 'leer'),
    ('CIUDADANO', 'pago', 'leer'), ('CIUDADANO', 'pago', 'crear'),
    ('CIUDADANO', 'ingreso', 'leer'), ('CIUDADANO', 'ingreso', 'crear'),
    ('CIUDADANO', 'ingreso', 'editar'), ('CIUDADANO', 'ingreso', 'eliminar'),

    ('GESTOR', 'tramite', 'leer'), ('GESTOR', 'tramite', 'editar'),
    ('GESTOR', 'tipo_tramite', 'leer'),
    ('GESTOR', 'pago', 'leer'), ('GESTOR', 'pago', 'editar'),
    ('GESTOR', 'usuario', 'leer'),

    ('AUDITOR', 'tramite', 'leer'), ('AUDITOR', 'tipo_tramite', 'leer'), ('AUDITOR', 'pago', 'leer'),
    ('AUDITOR', 'usuario', 'leer'), ('AUDITOR', 'rol', 'leer'), ('AUDITOR', 'bitacora', 'leer'),

    ('ADMINISTRADOR', 'tramite', 'leer'), ('ADMINISTRADOR', 'tramite', 'crear'),
    ('ADMINISTRADOR', 'tramite', 'editar'), ('ADMINISTRADOR', 'tramite', 'eliminar'),
    ('ADMINISTRADOR', 'tipo_tramite', 'leer'), ('ADMINISTRADOR', 'tipo_tramite', 'crear'),
    ('ADMINISTRADOR', 'tipo_tramite', 'editar'), ('ADMINISTRADOR', 'tipo_tramite', 'eliminar'),
    ('ADMINISTRADOR', 'pago', 'leer'), ('ADMINISTRADOR', 'pago', 'crear'),
    ('ADMINISTRADOR', 'pago', 'editar'), ('ADMINISTRADOR', 'pago', 'eliminar'),
    ('ADMINISTRADOR', 'usuario', 'leer'), ('ADMINISTRADOR', 'usuario', 'crear'),
    ('ADMINISTRADOR', 'usuario', 'editar'), ('ADMINISTRADOR', 'usuario', 'eliminar'),
    ('ADMINISTRADOR', 'rol', 'leer'), ('ADMINISTRADOR', 'rol', 'crear'),
    ('ADMINISTRADOR', 'rol', 'editar'), ('ADMINISTRADOR', 'rol', 'eliminar'),
    ('ADMINISTRADOR', 'bitacora', 'leer')
) AS v (rol, recurso, accion)
JOIN rol r ON r.nombre = v.rol
JOIN permiso p ON p.recurso = v.recurso AND p.accion = v.accion;
