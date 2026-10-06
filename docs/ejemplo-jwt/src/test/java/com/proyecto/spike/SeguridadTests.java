package com.proyecto.spike;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "app.jwt.secret=una-clave-de-prueba-de-al-menos-32-caracteres")
@AutoConfigureMockMvc
class SeguridadTests {

    @Autowired MockMvc mvc;

    private String login(String correo, String clave) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"correo\":\"" + correo + "\",\"clave\":\"" + clave + "\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    @Test
    void loginCorrectoDevuelveToken() throws Exception {
        String token = login("ciudadano@demo.com", "clave123");
        mvc.perform(get("/api/yo").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
    }

    @Test
    void claveIncorrectaNoEntra() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"correo\":\"ciudadano@demo.com\",\"clave\":\"mala\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void sinTokenEs401() throws Exception {
        mvc.perform(get("/api/yo")).andExpect(status().isUnauthorized());
    }

    @Test
    void tokenAlteradoEs401() throws Exception {
        String token = login("ciudadano@demo.com", "clave123");
        String alterado = token.substring(0, token.length() - 3) + "abc";
        mvc.perform(get("/api/yo").header("Authorization", "Bearer " + alterado))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void conTokenPeroSinPermisoEs403() throws Exception {
        String token = login("ciudadano@demo.com", "clave123");
        mvc.perform(get("/api/usuarios-eliminar").header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden());
    }

    @Test
    void conPermisoEs200() throws Exception {
        String token = login("admin@demo.com", "clave123");
        mvc.perform(get("/api/usuarios-eliminar").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk());
    }
}
