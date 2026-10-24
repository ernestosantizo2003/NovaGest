package com.proyecto.tramites;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Primera inicializacion real con roles separados. Nunca acepta una URL o un servidor remoto. */
class ProduccionInicializacionTests {

    private static PostgreSQLContainer container;
    private static String baseUrl;
    private static String admin;
    private static String adminPassword;
    private static final String PASSWORD = UUID.randomUUID().toString();

    @BeforeAll
    static void clusterExclusivo() throws Exception {
        String fixturePort = System.getProperty("novagest.pg.fixture.port");
        if (fixturePort == null) {
            container = new PostgreSQLContainer(DockerImageName.parse("postgres:18-bookworm"));
            container.start();
            baseUrl = "jdbc:postgresql://" + container.getHost() + ":" + container.getMappedPort(5432) + "/";
            admin = container.getUsername();
            adminPassword = container.getPassword();
        }
        else {
            int port = Integer.parseInt(fixturePort);
            assertThat(port).isBetween(1024, 65535).isNotEqualTo(5432);
            baseUrl = "jdbc:postgresql://127.0.0.1:" + port + "/";
            admin = "admin_fixture";
            adminPassword = "";
            String expectedDirectory = System.getProperty("novagest.pg.fixture.directory");
            assertThat(expectedDirectory).isNotBlank();
            assertThat(Path.of(scalar("postgres", admin, "SHOW data_directory"))).isEqualTo(Path.of(expectedDirectory));
        }
        // Fallar antes de DDL si este cluster ya contiene los roles de un entorno existente.
        assertThat(scalar("postgres", admin, "SELECT count(*) FROM pg_roles WHERE rolname LIKE 'tramites_prod_%'"))
                .isEqualTo("0");
        sql("postgres", admin, "CREATE ROLE tramites_prod_owner NOLOGIN; "
                + "CREATE ROLE tramites_prod_migrador LOGIN PASSWORD '" + PASSWORD + "'; "
                + "CREATE ROLE tramites_prod_app LOGIN PASSWORD '" + PASSWORD + "'; "
                + "GRANT tramites_prod_owner TO tramites_prod_migrador WITH INHERIT TRUE, SET TRUE;");
    }

    @BeforeEach
    void baseVacia() throws Exception {
        sql("postgres", admin, "DROP DATABASE IF EXISTS tramites_produccion WITH (FORCE)");
        sql("postgres", admin, "CREATE DATABASE tramites_produccion OWNER tramites_prod_owner");
        sql("tramites_produccion", admin, "CREATE SCHEMA tramites AUTHORIZATION tramites_prod_owner; "
                + "GRANT USAGE ON SCHEMA tramites TO tramites_prod_app; "
                + "ALTER ROLE tramites_prod_app IN DATABASE tramites_produccion SET search_path=tramites; "
                + "ALTER ROLE tramites_prod_migrador IN DATABASE tramites_produccion SET search_path=tramites;");
    }

    @AfterAll
    static void cerrar() {
        if (container != null) container.stop();
    }

    private static Connection connection(String db, String user) throws SQLException {
        return DriverManager.getConnection(baseUrl + db, user, user.equals(admin) ? adminPassword : PASSWORD);
    }

    private static void sql(String db, String user, String text) throws SQLException {
        try (Connection c = connection(db, user); var s = c.createStatement()) { s.execute(text); }
    }

    private static String scalar(String db, String user, String text) throws SQLException {
        try (Connection c = connection(db, user); var s = c.createStatement(); var r = s.executeQuery(text)) {
            r.next(); return r.getString(1);
        }
    }

    private Flyway flyway(boolean setOwner, boolean callbacks) {
        return Flyway.configure().dataSource(baseUrl + "tramites_produccion", "tramites_prod_migrador", PASSWORD)
                .defaultSchema("tramites").schemas("tramites").createSchemas(false)
                .initSql(setOwner ? "SET ROLE tramites_prod_owner" : "SELECT 1")
                .locations(callbacks ? new String[] {"classpath:db/migration", "classpath:db/produccion"}
                                     : new String[] {"classpath:db/migration"}).load();
    }

    @Test
    void membresiaSinSetRoleCreaObjetosDelMigradorYNoDaAccesoALaApp() throws Exception {
        assertThat(flyway(false, false).migrate().migrationsExecuted).isEqualTo(9);
        assertThat(scalar("tramites_produccion", admin,
                "SELECT pg_get_userbyid(relowner) FROM pg_class WHERE oid='tramites.usuario'::regclass"))
                .isEqualTo("tramites_prod_migrador");
        assertThatThrownBy(() -> sql("tramites_produccion", "tramites_prod_app", "SELECT * FROM tramites.usuario"))
                .isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("42501");
    }

    @Test
    void setRoleSinGrantsTampocoPermiteLeerElModelo() throws Exception {
        flyway(true, false).migrate();
        assertThatThrownBy(() -> sql("tramites_produccion", "tramites_prod_app", "SELECT * FROM tramites.rol"))
                .isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("42501");
    }

    @Test
    void nueveMigracionesObjetosDelOwnerYPermisosDeNegocioSinHistorial() throws Exception {
        assertThat(flyway(true, true).migrate().migrationsExecuted).isEqualTo(9);
        assertThat(scalar("tramites_produccion", admin, "SELECT count(*) FROM pg_class c JOIN pg_namespace n "
                + "ON n.oid=c.relnamespace WHERE n.nspname='tramites' AND c.relowner<>'tramites_prod_owner'::regrole"))
                .isEqualTo("0");
        assertThat(scalar("tramites_produccion", admin, "SELECT count(*) FROM tramites.flyway_schema_history WHERE success"))
                .isEqualTo("9");
        assertThat(scalar("tramites_produccion", "tramites_prod_app", "SELECT count(*) FROM tramites.permiso"))
                .isEqualTo("25");
        assertThat(scalar("tramites_produccion", admin, "SELECT has_sequence_privilege('tramites_prod_app',"
                + "'tramites.usuario_id_seq','USAGE')" )).isEqualTo("t");
        for (String command : new String[] {"SELECT * FROM tramites.flyway_schema_history",
                "EXPLAIN INSERT INTO tramites.flyway_schema_history(installed_rank) VALUES(99)",
                "EXPLAIN UPDATE tramites.flyway_schema_history SET success=false",
                "EXPLAIN DELETE FROM tramites.flyway_schema_history", "SET ROLE tramites_prod_owner"}) {
            assertThatThrownBy(() -> sql("tramites_produccion", "tramites_prod_app", command))
                    .isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("42501");
        }
        assertThat(scalar("tramites_produccion", admin, "SELECT has_table_privilege('tramites_prod_app',"
                + "'tramites.bitacora','UPDATE,DELETE,TRUNCATE')" )).isEqualTo("f");
        assertThat(scalar("tramites_produccion", admin, "SELECT has_sequence_privilege('tramites_prod_app',"
                + "'tramites.usuario_id_seq','UPDATE')" )).isEqualTo("f");
    }

    @Test
    void segundoMigrateNoAgregaMigracionesYValidateFunciona() throws Exception {
        Flyway f = flyway(true, true); f.migrate();
        String before = scalar("tramites_produccion", admin, "SELECT md5(string_agg(row_to_json(h)::text,'' ORDER BY installed_rank)) "
                + "FROM tramites.flyway_schema_history h");
        assertThat(flyway(true, true).migrate().migrationsExecuted).isZero();
        assertThat(flyway(true, true).validateWithResult().validationSuccessful).isTrue();
        assertThat(scalar("tramites_produccion", admin, "SELECT md5(string_agg(row_to_json(h)::text,'' ORDER BY installed_rank)) "
                + "FROM tramites.flyway_schema_history h")).isEqualTo(before);
    }

    @Test
    void callbackRechazaOmitirSetRole() {
        assertThatThrownBy(() -> flyway(false, true).migrate()).hasStackTraceContaining("42501");
    }

    @Test
    void defaultsAmpliosSeRechazanAntesDeV2() throws Exception {
        sql("tramites_produccion", admin, "ALTER DEFAULT PRIVILEGES FOR ROLE tramites_prod_owner IN SCHEMA tramites "
                + "GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO tramites_prod_app");
        assertThatThrownBy(() -> flyway(true, true).migrate()).hasStackTraceContaining("defaults de tablas");
        assertThat(scalar("tramites_produccion", admin, "SELECT to_regclass('tramites.usuario') IS NULL")).isEqualTo("t");
    }

    @Test
    void callbackRechazaHistorialExpuestoAPublic() throws Exception {
        flyway(true, true).migrate();
        sql("tramites_produccion", admin, "GRANT SELECT ON tramites.flyway_schema_history TO PUBLIC");
        assertThatThrownBy(() -> flyway(true, true).migrate()).hasStackTraceContaining("historial Flyway");
    }

    @Test
    void callbackRechazaAccesoPorSetRoleIntermedioSinHerencia() throws Exception {
        flyway(true, true).migrate();
        sql("postgres", admin, "CREATE ROLE lector_fixture NOLOGIN; "
                + "GRANT lector_fixture TO tramites_prod_app WITH INHERIT FALSE, SET TRUE");
        try {
            sql("tramites_produccion", admin, "GRANT SELECT ON tramites.flyway_schema_history TO lector_fixture");
            assertThat(scalar("tramites_produccion", admin, "SELECT has_table_privilege('tramites_prod_app',"
                    + "'tramites.flyway_schema_history','SELECT')")).isEqualTo("f");
            assertThatThrownBy(() -> flyway(true, true).migrate()).hasStackTraceContaining("historial Flyway");
        }
        finally {
            sql("tramites_produccion", admin, "REVOKE SELECT ON tramites.flyway_schema_history FROM lector_fixture");
            sql("postgres", admin, "REVOKE lector_fixture FROM tramites_prod_app; DROP ROLE lector_fixture");
        }
    }

    @Test
    void appNoPuedeUsarLaConexionDeMigraciones() {
        assertThatThrownBy(() -> Flyway.configure().dataSource(baseUrl + "tramites_produccion", "tramites_prod_app", PASSWORD)
                .initSql("SET ROLE tramites_prod_owner").defaultSchema("tramites")
                .locations("classpath:db/migration", "classpath:db/produccion").load().migrate())
                .hasStackTraceContaining("42501");
    }

    @Test
    void permisoSetFalseDelMigradorImpideCrearObjetos() throws Exception {
        sql("postgres", admin, "GRANT tramites_prod_owner TO tramites_prod_migrador WITH SET FALSE");
        try {
            assertThatThrownBy(() -> flyway(true, true).migrate()).hasStackTraceContaining("42501");
            assertThat(scalar("tramites_produccion", admin, "SELECT to_regclass('tramites.usuario') IS NULL")).isEqualTo("t");
        }
        finally {
            sql("postgres", admin, "GRANT tramites_prod_owner TO tramites_prod_migrador WITH SET TRUE");
        }
    }

    @Test
    void primerArranqueCompletoValidaHibernateHealthRegistroYLogin() throws Exception {
        try (var context = new SpringApplicationBuilder(NovaGestApplication.class).run(
                "--spring.profiles.active=prod,dba-produccion", "--server.address=127.0.0.1", "--server.port=0",
                "--spring.datasource.url=" + baseUrl + "tramites_produccion?currentSchema=tramites",
                "--spring.datasource.username=tramites_prod_app", "--spring.datasource.password=" + PASSWORD,
                "--spring.flyway.user=tramites_prod_migrador", "--spring.flyway.password=" + PASSWORD,
                "--app.jwt.secret=" + UUID.randomUUID() + UUID.randomUUID(),
                "--app.pagos.directorio=target/comprobantes-inicializacion", "--app.divisas.tarea-diaria.habilitada=false",
                "--app.limite.habilitado=false", "--spring.config.import=", "--debug=false",
                "--logging.level.org.springframework=INFO", "--logging.level.org.hibernate.SQL=WARN")) {
            assertThat(context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            HttpClient client = HttpClient.newHttpClient();
            URI base = URI.create("http://127.0.0.1:" + port);
            var health = client.send(HttpRequest.newBuilder(base.resolve("/actuator/health")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(health.statusCode()).isEqualTo(200);
            assertThat(health.body()).contains("\"UP\"");
            String clave = UUID.randomUUID().toString();
            for (String endpoint : new String[] {"registro", "login"}) {
                String body = "{\"nombre\":\"Prueba aislada\",\"correo\":\"aislada@novagest.test\",\"clave\":\"" + clave + "\"}";
                var response = client.send(HttpRequest.newBuilder(base.resolve("/api/auth/" + endpoint))
                        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).as(endpoint).isEqualTo(endpoint.equals("registro") ? 201 : 200);
                if (endpoint.equals("login")) assertThat(response.body()).contains("accessToken", "refreshToken");
            }
            assertThat(scalar("tramites_produccion", "tramites_prod_app", "SELECT count(*) FROM tramites.usuario"))
                    .isEqualTo("1");
        }
    }
}
