package com.proyecto.spike;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    record LoginRequest(@NotBlank String correo, @NotBlank String clave) {}

    private final AuthenticationManager authManager;
    private final JwtEncoder encoder;

    AuthController(AuthenticationManager authManager, JwtEncoder encoder) {
        this.authManager = authManager;
        this.encoder = encoder;
    }

    @PostMapping("/login")
    Map<String, Object> login(@Valid @RequestBody LoginRequest req) {
        // Clave mala lanza BadCredentialsException: mismo error para correo o clave incorrectos
        Authentication auth = authManager.authenticate(
            UsernamePasswordAuthenticationToken.unauthenticated(req.correo(), req.clave()));

        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
            .subject(auth.getName())
            .issuedAt(now)
            .expiresAt(now.plus(Duration.ofMinutes(15)))
            .id(UUID.randomUUID().toString())
            .build();
        String token = encoder.encode(
            JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return Map.of("accessToken", token, "expiraEnSegundos", 900);
    }
}
