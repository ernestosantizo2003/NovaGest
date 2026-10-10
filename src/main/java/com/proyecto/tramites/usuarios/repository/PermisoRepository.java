package com.proyecto.tramites.usuarios.repository;

import com.proyecto.tramites.usuarios.entity.Permiso;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PermisoRepository extends JpaRepository<Permiso, Long> {

	Optional<Permiso> findByRecursoAndAccion(String recurso, String accion);

}
