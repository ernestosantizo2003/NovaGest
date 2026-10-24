-- Callback optativo del perfil dba-produccion. No altera V1..V10 ni crea roles.
DO $$
BEGIN
    IF current_database() <> 'tramites_produccion'
       OR session_user <> 'tramites_prod_migrador'
       OR current_user <> 'tramites_prod_owner' THEN
        RAISE EXCEPTION 'DBA: base, login o SET ROLE de Flyway incorrectos' USING ERRCODE = '42501';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_namespace n JOIN pg_roles r ON r.oid = n.nspowner
                   WHERE n.nspname = 'tramites' AND r.rolname = 'tramites_prod_owner')
       OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'tramites_prod_owner'
                      AND NOT rolcanlogin AND NOT rolsuper AND NOT rolcreaterole)
       OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'tramites_prod_app'
                      AND NOT rolsuper AND NOT rolcreaterole AND NOT rolcreatedb
                      AND NOT rolreplication AND NOT rolbypassrls) THEN
        RAISE EXCEPTION 'DBA: esquema o atributos de roles incorrectos' USING ERRCODE = '42501';
    END IF;
    IF NOT has_schema_privilege('tramites_prod_app', 'tramites', 'USAGE')
       OR has_schema_privilege('tramites_prod_app', 'tramites', 'CREATE')
       OR pg_has_role('tramites_prod_app', 'tramites_prod_owner', 'SET')
       OR pg_has_role('tramites_prod_app', 'tramites_prod_migrador', 'SET') THEN
        RAISE EXCEPTION 'DBA: aislamiento de la aplicacion incorrecto' USING ERRCODE = '42501';
    END IF;
    -- Los defaults amplios alcanzarian tambien flyway_schema_history. No repararlos
    -- silenciosamente ni repetir el incidente de staging: requieren revision DBA.
    IF EXISTS (
        SELECT 1 FROM pg_default_acl d
        CROSS JOIN LATERAL aclexplode(d.defaclacl) a
        WHERE d.defaclrole = 'tramites_prod_owner'::regrole AND d.defaclobjtype = 'r'
          AND d.defaclnamespace IN (0, 'tramites'::regnamespace)
          AND (a.grantee = 0 OR a.grantee IN (
              SELECT oid FROM pg_roles
              WHERE pg_has_role('tramites_prod_app', oid, 'USAGE')
                 OR pg_has_role('tramites_prod_app', oid, 'SET')))
    ) THEN
        RAISE EXCEPTION 'DBA: defaults de tablas exponen el historial Flyway' USING ERRCODE = '42501';
    END IF;
END $$;
