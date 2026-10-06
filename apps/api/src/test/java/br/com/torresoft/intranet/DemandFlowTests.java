package br.com.torresoft.intranet;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {"app.bootstrap.arthur-password=test-password-arthur", "app.bootstrap.felipe-password=test-password-felipe"})
@AutoConfigureMockMvc
@Testcontainers
class DemandFlowTests {
  @Container @ServiceConnection static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.4");
  @Autowired MockMvc mvc;

  @Test void criaFiltraEAlteraDemandaEmLote() throws Exception {
    mvc.perform(get("/api/demandas")).andExpect(status().isUnauthorized());
    String created = mvc.perform(post("/api/demandas").with(user("arthur")).with(csrf())
      .contentType(MediaType.APPLICATION_JSON)
      .content("""
        {"titulo":"Corrigir portal","descricao":"Adicionar imagem","status":"AGUARDANDO_DESENVOLVIMENTO"}
        """))
      .andExpect(status().isCreated()).andExpect(jsonPath("$.titulo").value("Corrigir portal"))
      .andReturn().getResponse().getContentAsString();
    var matcher = Pattern.compile("\"id\"\\s*:\\s*(\\d+)").matcher(created);
    if (!matcher.find()) throw new AssertionError("Resposta sem id: " + created);
    long id = Long.parseLong(matcher.group(1));
    mvc.perform(post("/api/demandas/lote").with(user("felipe")).with(csrf())
      .contentType(MediaType.APPLICATION_JSON)
      .content("{\"ids\":[" + id + "],\"status\":\"EM_TESTE\",\"alterarResponsavel\":true,\"responsavelId\":2}"))
      .andExpect(status().isOk()).andExpect(jsonPath("$.alteradas").value(1));
    mvc.perform(get("/api/demandas").with(user("arthur")).param("texto", "portal").param("status", "EM_TESTE"))
      .andExpect(status().isOk()).andExpect(jsonPath("$[0].responsavel").value("Felipe"));
    mvc.perform(post("/api/demandas/lote").with(user("arthur")).with(csrf())
      .contentType(MediaType.APPLICATION_JSON)
      .content("{\"ids\":[999999],\"status\":\"REABERTA\",\"alterarResponsavel\":false}"))
      .andExpect(status().isNotFound());
  }
}
