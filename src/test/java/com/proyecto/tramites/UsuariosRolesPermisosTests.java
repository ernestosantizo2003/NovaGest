package com.proyecto.tramites;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.proyecto.tramites.usuarios.entity.Permiso;
import com.proyecto.tramites.usuarios.entity.Rol;
import com.proyecto.tramites.usuarios.entity.Usuario;
import com.proyecto.tramites.usuarios.repository.PermisoRepository;
import com.proyecto.tramites.usuarios.repository.RolRepository;
import com.proyecto.tramites.usuarios.repository.UsuarioRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tarjeta 7: la migracion V2 carga la matriz de roles y permisos del plan y las
 * entidades la leen bien. Cada prueba se revierte al terminar (@Transactional).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UsuariosRolesPermisosTests {

	@Autowired
	private RolRepository roles;

	@Autowired
	private PermisoRepository permisos;

	@Autowired
	private UsuarioRepository usuarios;

	@Autowired
	private EntityManager em;

	private Set<String> permisosDe(String rol) {
		return roles.findByNombre(rol).orElseThrow().getPermisos().stream()
				.map(Permiso::getNombre)
				.collect(Collectors.toSet());
	}

	@Test
	void existenLosCuatroRolesIniciales() {
		assertThat(roles.findAll()).extracting(Rol::getNombre)
				.containsExactlyInAnyOrder("CIUDADANO", "GESTOR", "AUDITOR", "ADMINISTRADOR");
	}

	@Test
	void existenLos25PermisosIniciales() {
		assertThat(permisos.count()).isEqualTo(25);
	}

	@Test
	void soloElCiudadanoTienePermisosDeIngreso() {
		assertThat(permisosDe("CIUDADANO")).contains(
				"ingreso:leer", "ingreso:crear", "ingreso:editar", "ingreso:eliminar");
		for (String rol : List.of("GESTOR", "AUDITOR", "ADMINISTRADOR")) {
			assertThat(permisosDe(rol)).noneMatch(p -> p.startsWith("ingreso:"));
		}
	}

	@Test
	void soloElAdministradorPuedeEliminarUsuarios() {
		assertThat(permisosDe("ADMINISTRADOR")).contains("usuario:eliminar");
		for (String rol : List.of("CIUDADANO", "GESTOR", "AUDITOR")) {
			assertThat(permisosDe(rol)).doesNotContain("usuario:eliminar");
		}
	}

	@Test
	void elAuditorSoloTienePermisosDeLectura() {
		assertThat(permisosDe("AUDITOR")).isNotEmpty().allMatch(p -> p.endsWith(":leer"));
	}

	@Test
	void elGestorValidaPagosPeroNoLosCrea() {
		assertThat(permisosDe("GESTOR")).contains("pago:editar", "pago:leer").doesNotContain("pago:crear");
	}

	@Test
	void nadieEditaNiBorraLaBitacora() {
		assertThat(permisos.findByRecursoAndAccion("bitacora", "leer")).isPresent();
		for (String accion : List.of("crear", "editar", "eliminar")) {
			assertThat(permisos.findByRecursoAndAccion("bitacora", accion)).isEmpty();
		}
	}

	@Test
	void elCorreoEsUnicoSinImportarMayusculas() {
		usuarios.saveAndFlush(new Usuario("ana@demo.com", "Ana", "hash"));
		assertThatThrownBy(() -> usuarios.saveAndFlush(new Usuario("ANA@demo.com", "Otra Ana", "hash")))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void unUsuarioTraeSusPermisosPorSusRoles() {
		Usuario ana = new Usuario("ana@demo.com", "Ana", "hash");
		ana.getRoles().add(roles.findByNombre("CIUDADANO").orElseThrow());
		usuarios.saveAndFlush(ana);
		em.clear();

		Usuario encontrada = usuarios.findByCorreoIgnoreCaseAndEliminadoEnIsNull("ANA@DEMO.COM").orElseThrow();

		assertThat(encontrada.permisosEfectivos()).contains("tramite:crear", "ingreso:leer")
				.doesNotContain("usuario:eliminar");
	}

	@Test
	void eliminarLogicamenteOcultaAlUsuarioYLiberaElCorreo() {
		Usuario ana = usuarios.saveAndFlush(new Usuario("ana@demo.com", "Ana", "hash"));
		ana.eliminarLogicamente();
		usuarios.saveAndFlush(ana);

		assertThat(usuarios.findByCorreoIgnoreCaseAndEliminadoEnIsNull("ana@demo.com")).isEmpty();
		assertThat(usuarios.findById(ana.getId())).isPresent();
		usuarios.saveAndFlush(new Usuario("ana@demo.com", "Ana nueva", "hash"));
		assertThat(usuarios.existsByCorreoIgnoreCaseAndEliminadoEnIsNull("ana@demo.com")).isTrue();
	}

}
