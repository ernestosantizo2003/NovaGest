package com.proyecto.tramites.usuarios.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Un permiso es un recurso mas una accion, por ejemplo tramite:editar. */
@Entity
@Table(name = "permiso")
public class Permiso {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 50)
	private String recurso;

	@Column(nullable = false, length = 20)
	private String accion;

	protected Permiso() {
	}

	public Permiso(String recurso, String accion) {
		this.recurso = recurso;
		this.accion = accion;
	}

	/** Nombre con el que el codigo pregunta por el permiso: recurso:accion. */
	public String getNombre() {
		return recurso + ":" + accion;
	}

	public Long getId() {
		return id;
	}

	public String getRecurso() {
		return recurso;
	}

	public String getAccion() {
		return accion;
	}

}
