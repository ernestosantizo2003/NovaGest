package com.proyecto.tramites.usuarios.repository;

import com.proyecto.tramites.usuarios.entity.Rol;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RolRepository extends JpaRepository<Rol, Long> {

	@EntityGraph(attributePaths = "permisos")
	Optional<Rol> findByNombre(String nombre);

}
