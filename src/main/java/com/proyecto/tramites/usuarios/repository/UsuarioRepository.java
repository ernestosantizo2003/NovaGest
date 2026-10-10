package com.proyecto.tramites.usuarios.repository;

import com.proyecto.tramites.usuarios.entity.Usuario;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UsuarioRepository extends JpaRepository<Usuario, Long> {

	/** Busca sin importar mayusculas y ignora los eliminados. Trae roles y permisos en una sola consulta. */
	@EntityGraph(attributePaths = { "roles", "roles.permisos" })
	Optional<Usuario> findByCorreoIgnoreCaseAndEliminadoEnIsNull(String correo);

	boolean existsByCorreoIgnoreCaseAndEliminadoEnIsNull(String correo);

}
