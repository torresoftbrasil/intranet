package br.com.torresoft.intranet;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.regex.Pattern;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {"app.bootstrap.arthur-password=test-password-arthur", "app.bootstrap.felipe-password=test-password-felipe", "app.uploads-dir=${java.io.tmpdir}/intranet-test-uploads"})
@AutoConfigureMockMvc
@Testcontainers
class DemandFlowTests {
  @Container @ServiceConnection static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.4");
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;

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
    ByteArrayOutputStream imageBytes = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", imageBytes);
    mvc.perform(multipart("/api/demandas/" + id + "/comentarios")
      .file(new MockMultipartFile("imagem", "print.png", "image/png", imageBytes.toByteArray()))
      .param("texto", "Primeiro comentário").with(user("arthur")).with(csrf()))
      .andExpect(status().isCreated())
      .andExpect(jsonPath("$.autor").value("Arthur"))
      .andExpect(jsonPath("$.imagens[0].nome").value("print.png"));
    mvc.perform(multipart("/api/demandas/" + id + "/comentarios")
      .param("texto", "Resposta do Felipe").with(user("felipe")).with(csrf()))
      .andExpect(status().isCreated());
    mvc.perform(get("/api/demandas/" + id + "/comentarios").with(user("arthur")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$[0].texto").value("Resposta do Felipe"))
      .andExpect(jsonPath("$[1].texto").value("Primeiro comentário"));
    mvc.perform(post("/api/demandas").with(user("felipe")).with(csrf())
      .contentType(MediaType.APPLICATION_JSON)
      .content("{\"titulo\":\"Demanda do Felipe\"}"))
      .andExpect(status().isCreated());
    mvc.perform(get("/api/demandas/recentes").with(user("arthur")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$[0].titulo").value("Corrigir portal"))
      .andExpect(jsonPath("$[1]").doesNotExist());
    mvc.perform(post("/api/demandas/lote").with(user("felipe")).with(csrf())
      .contentType(MediaType.APPLICATION_JSON)
      .content("{\"ids\":[" + id + "],\"status\":\"EM_TESTE\",\"alterarResponsavel\":true,\"responsavelId\":2}"))
      .andExpect(status().isOk()).andExpect(jsonPath("$.alteradas").value(1));
    mvc.perform(get("/api/demandas").with(user("arthur")).param("texto", "portal").param("status", "EM_TESTE"))
      .andExpect(status().isOk()).andExpect(jsonPath("$[0].responsavel").value("Felipe"));
    mvc.perform(put("/api/demandas/" + id).with(user("arthur")).with(csrf())
      .contentType(MediaType.APPLICATION_JSON)
      .content("{\"titulo\":\"Corrigir portal\",\"descricao\":\"Adicionar imagem\",\"status\":\"ENCERRADA\",\"responsavelId\":2}"))
      .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ENCERRADA"));
    mvc.perform(get("/api/demandas").with(user("felipe")).param("abertas", "true").param("texto", "portal"))
      .andExpect(status().isOk()).andExpect(jsonPath("$[0]").doesNotExist());
    mvc.perform(get("/api/demandas").with(user("arthur")).param("status", "ENCERRADA"))
      .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id));
    if (jdbc.queryForObject("SELECT encerrada_em IS NOT NULL FROM demanda WHERE id = ?", Boolean.class, id) != Boolean.TRUE)
      throw new AssertionError("Demanda encerrada sem data de encerramento");
    mvc.perform(put("/api/demandas/" + id).with(user("arthur")).with(csrf())
      .contentType(MediaType.APPLICATION_JSON)
      .content("{\"titulo\":\"Corrigir portal\",\"descricao\":\"Adicionar imagem\",\"status\":\"REABERTA\",\"responsavelId\":2}"))
      .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REABERTA"));
    if (jdbc.queryForObject("SELECT encerrada_em IS NULL FROM demanda WHERE id = ?", Boolean.class, id) != Boolean.TRUE)
      throw new AssertionError("Demanda reaberta ainda tem data de encerramento");
    mvc.perform(post("/api/demandas/lote").with(user("arthur")).with(csrf())
      .contentType(MediaType.APPLICATION_JSON)
      .content("{\"ids\":[999999],\"status\":\"REABERTA\",\"alterarResponsavel\":false}"))
      .andExpect(status().isNotFound());
  }
}
