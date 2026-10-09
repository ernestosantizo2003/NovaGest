package com.proyecto.tramites;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class FlywayMigracionTests {

	@Autowired
	private Flyway flyway;

	@Test
	void laPrimeraMigracionCorreAlArrancar() {
		assertThat(flyway.info().applied()).anySatisfy(migracion -> {
			assertThat(migracion.getVersion().getVersion()).isEqualTo("1");
			assertThat(migracion.getState()).isEqualTo(MigrationState.SUCCESS);
		});
		assertThat(flyway.info().pending()).isEmpty();
	}

}
