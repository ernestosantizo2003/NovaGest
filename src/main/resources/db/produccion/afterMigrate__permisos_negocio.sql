-- Callback optativo: permisos explicitos de negocio, nunca GRANT ON ALL TABLES.
DO $$
DECLARE
    tabla text;
BEGIN
    IF current_database() <> 'tramites_produccion'
       OR session_user <> 'tramites_prod_migrador'
       OR current_user <> 'tramites_prod_owner' THEN
        RAISE EXCEPTION 'DBA: identidad incorrecta al conceder permisos' USING ERRCODE = '42501';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
               WHERE n.nspname = 'tramites' AND c.relkind IN ('r','p','i','I','S','v','m','f')
                 AND c.relowner <> 'tramites_prod_owner'::regrole) THEN
        RAISE EXCEPTION 'DBA: objetos con propietario inesperado' USING ERRCODE = '42501';
    END IF;
    IF has_table_privilege('tramites_prod_app', 'tramites.flyway_schema_history',
                          'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER,MAINTAIN')
       OR has_any_column_privilege('tramites_prod_app', 'tramites.flyway_schema_history',
                                   'SELECT,INSERT,UPDATE,REFERENCES')
       OR EXISTS (SELECT 1 FROM pg_roles r
                  WHERE pg_has_role('tramites_prod_app', r.oid, 'SET') AND
                        (has_table_privilege(r.oid, 'tramites.flyway_schema_history',
                                             'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER,MAINTAIN')
                         OR has_any_column_privilege(r.oid, 'tramites.flyway_schema_history',
                                                      'SELECT,INSERT,UPDATE,REFERENCES'))) THEN
        RAISE EXCEPTION 'DBA: la aplicacion puede acceder al historial Flyway' USING ERRCODE = '42501';
    END IF;
    GRANT SELECT ON tramites.permiso TO tramites_prod_app;
    GRANT SELECT, INSERT ON tramites.bitacora, tramites.tramite_historial TO tramites_prod_app;
    FOREACH tabla IN ARRAY ARRAY['usuario','rol','usuario_rol','rol_permiso','token_renovacion',
                                  'tipo_tramite','campo_tipo_tramite','tramite','ingreso','pago','tipo_cambio']
    LOOP
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE tramites.%I TO tramites_prod_app', tabla);
    END LOOP;
    -- Solo las secuencias de las tablas de negocio; no futuros objetos ajenos.
    FOR tabla IN SELECT DISTINCT pg_get_serial_sequence('tramites.' || quote_ident(c.relname), a.attname)
                 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                 JOIN pg_attribute a ON a.attrelid = c.oid AND a.attidentity <> ''
                 WHERE n.nspname = 'tramites' AND c.relname IN
                    ('usuario','rol','permiso','token_renovacion','tipo_tramite','campo_tipo_tramite',
                     'tramite','tramite_historial','bitacora','ingreso','pago','tipo_cambio')
    LOOP
        EXECUTE format('GRANT USAGE, SELECT ON SEQUENCE %s TO tramites_prod_app', tabla);
    END LOOP;
END $$;
