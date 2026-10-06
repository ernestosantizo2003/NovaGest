package com.proyecto.spike;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

/** Usuarios de juguete en memoria. En NovaGest vendran de PostgreSQL (tarjeta 7). */
@Configuration
public class UsersConfig {

    @Bean
    UserDetailsService users(PasswordEncoder encoder) {
        return new InMemoryUserDetailsManager(
            User.withUsername("ciudadano@demo.com").password(encoder.encode("clave123"))
                .authorities("tramite:leer", "tramite:crear").build(),
            User.withUsername("admin@demo.com").password(encoder.encode("clave123"))
                .authorities("tramite:leer", "usuario:eliminar").build());
    }
}
