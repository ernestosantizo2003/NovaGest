package com.proyecto.tramites.usuarios.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/** Persona que entra al sistema. Se elimina de forma logica: el registro se marca, no se pierde. */
@Entity
@Table(name = "usuario")
public class Usuario {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 254)
	private String correo;

	@Column(nullable = false, length = 120)
	private String nombre;

	@Column(name = "hash_contrasena", nullable = false, length = 100)
	private String hashContrasena;

	@Column(nullable = false)
	private boolean activo = true;

	@Column(name = "intentos_fallidos", nullable = false)
	private int intentosFallidos;

	@Column(name = "bloqueado_hasta")
	private Instant bloqueadoHasta;

	@ManyToMany(fetch = FetchType.LAZY)
	@JoinTable(name = "usuario_rol",
			joinColumns = @JoinColumn(name = "usuario_id"),
			inverseJoinColumns = @JoinColumn(name = "rol_id"))
	private Set<Rol> roles = new HashSet<>();

	@Column(name = "creado_en", nullable = false, updatable = false)
	private Instant creadoEn;

	@Column(name = "actualizado_en", nullable = false)
	private Instant actualizadoEn;

	@Column(name = "eliminado_en")
	private Instant eliminadoEn;

	protected Usuario() {
	}

	public Usuario(String correo, String nombre, String hashContrasena) {
		this.correo = correo;
		this.nombre = nombre;
		this.hashContrasena = hashContrasena;
	}

	@PrePersist
	void alCrear() {
		creadoEn = Instant.now();
		actualizadoEn = creadoEn;
	}

	@PreUpdate
	void alActualizar() {
		actualizadoEn = Instant.now();
	}

	/** Todos los permisos de todos sus roles, con el formato recurso:accion. */
	public Set<String> permisosEfectivos() {
		return roles.stream()
				.flatMap(rol -> rol.getPermisos().stream())
				.map(Permiso::getNombre)
				.collect(Collectors.toSet());
	}

	public boolean estaBloqueado(Instant ahora) {
		return bloqueadoHasta != null && bloqueadoHasta.isAfter(ahora);
	}

	/** Eliminacion logica: el registro queda en la base y libera el correo. */
	public void eliminarLogicamente() {
		eliminadoEn = Instant.now();
		activo = false;
	}

	public Long getId() {
		return id;
	}

	public String getCorreo() {
		return correo;
	}

	public String getNombre() {
		return nombre;
	}

	public void setNombre(String nombre) {
		this.nombre = nombre;
	}

	public String getHashContrasena() {
		return hashContrasena;
	}

	public void setHashContrasena(String hashContrasena) {
		this.hashContrasena = hashContrasena;
	}

	public boolean isActivo() {
		return activo;
	}

	public void setActivo(boolean activo) {
		this.activo = activo;
	}

	public int getIntentosFallidos() {
		return intentosFallidos;
	}

	public void setIntentosFallidos(int intentosFallidos) {
		this.intentosFallidos = intentosFallidos;
	}

	public Instant getBloqueadoHasta() {
		return bloqueadoHasta;
	}

	public void setBloqueadoHasta(Instant bloqueadoHasta) {
		this.bloqueadoHasta = bloqueadoHasta;
	}

	public Set<Rol> getRoles() {
		return roles;
	}

	public Instant getCreadoEn() {
		return creadoEn;
	}

	public Instant getActualizadoEn() {
		return actualizadoEn;
	}

	public Instant getEliminadoEn() {
		return eliminadoEn;
	}

}
