package com.proyecto.spike;

import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class DemoController {

    @GetMapping("/yo")
    Map<String, Object> yo(Authentication auth) {
        List<String> permisos = auth.getAuthorities().stream().map(Object::toString).sorted().toList();
        return Map.of("usuario", auth.getName(), "permisos", permisos);
    }

    @GetMapping("/tramites")
    @PreAuthorize("hasAuthority('tramite:leer')")
    List<String> tramites() {
        return List.of("boleto de ornato", "pago de IGSS");
    }

    @GetMapping("/usuarios-eliminar")
    @PreAuthorize("hasAuthority('usuario:eliminar')")
    String soloAdmin() {
        return "ok";
    }
}
