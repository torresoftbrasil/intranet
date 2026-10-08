package br.com.torresoft.intranet;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {"app.bootstrap.arthur-password=test-password-arthur", "app.bootstrap.felipe-password=test-password-felipe", "app.uploads-dir=${java.io.tmpdir}/intranet-test-uploads", "app.ai-token=test-hub-ai-token"})
@AutoConfigureMockMvc
@Testcontainers
class DemandFlowTests {
  @Container @ServiceConnection static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.4");
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;

  @Test @Transactional void filaIaIncluiContextoEEsperaRevisaoHumana() throws Exception {
    mvc.perform(get("/api/ia/demandas")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/ia/demandas").with(user("arthur"))).andExpect(status().isUnauthorized());
    String created = mvc.perform(post("/api/demandas").with(user("arthur")).with(csrf())
      .contentType(MediaType.APPLICATION_JSON)
      .content("{\"titulo\":\"Demanda para IA\",\"descricao\":\"<p>Detalhes</p>\",\"destinadaIa\":true}"))
      .andExpect(status().isCreated()).andExpect(jsonPath("$.iaEstado").value("PENDENTE"))
      .andExpect(jsonPath("$.status").value("DESENVOLVIMENTO_EM_PROGRESSO"))
      .andExpect(jsonPath("$.responsavel").value("Zyven"))
      .andReturn().getResponse().getContentAsString();
    var matcher = Pattern.compile("\"id\"\\s*:\\s*(\\d+)").matcher(created);
    if (!matcher.find()) throw new AssertionError("Resposta sem id: " + created);
    long id = Long.parseLong(matcher.group(1));
    String second = mvc.perform(post("/api/demandas").with(user("arthur")).with(csrf())
      .contentType(MediaType.APPLICATION_JSON).content("{\"titulo\":\"Outra demanda\"}"))
      .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    var secondMatcher = Pattern.compile("\"id\"\\s*:\\s*(\\d+)").matcher(second);
    if (!secondMatcher.find()) throw new AssertionError("Resposta sem id: " + second);
    long secondId = Long.parseLong(secondMatcher.group(1));
    mvc.perform(post("/api/demandas/" + secondId + "/destinar-ia").with(user("arthur")).with(csrf()))
      .andExpect(status().isOk()).andExpect(jsonPath("$.responsavel").value("Zyven"))
      .andExpect(jsonPath("$.status").value("DESENVOLVIMENTO_EM_PROGRESSO"));
    String ordinary = mvc.perform(post("/api/demandas").with(user("arthur")).with(csrf())
      .contentType(MediaType.APPLICATION_JSON).content("{\"titulo\":\"Demanda humana\"}"))
      .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    var ordinaryMatcher = Pattern.compile("\"id\"\\s*:\\s*(\\d+)").matcher(ordinary);
    if (!ordinaryMatcher.find()) throw new AssertionError("Resposta sem id: " + ordinary);
    long ordinaryId = Long.parseLong(ordinaryMatcher.group(1));
    mvc.perform(get("/api/ia/demandas/" + ordinaryId).header("Authorization", "Bearer test-hub-ai-token"))
      .andExpect(status().isOk()).andExpect(jsonPath("$.titulo").value("Demanda humana"))
      .andExpect(jsonPath("$.iaEstado").value(""));
    mvc.perform(get("/api/ia/demandas/panorama").header("Authorization", "Bearer test-hub-ai-token")
      .param("tamanho", "1"))
      .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(jdbc.queryForObject("SELECT count(*) FROM demanda", Long.class)))
      .andExpect(jsonPath("$.porStatus.DESENVOLVIMENTO_EM_PROGRESSO").isNumber())
      .andExpect(jsonPath("$.porIaEstado.NAO_DESTINADA").isNumber())
      .andExpect(jsonPath("$.demandas.length()").value(1));
    ByteArrayOutputStream imageBytes = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", imageBytes);
    mvc.perform(multipart("/api/demandas/" + id + "/anexos")
      .file(new MockMultipartFile("arquivo", "descricao.png", "image/png", imageBytes.toByteArray()))
      .with(user("arthur")).with(csrf())).andExpect(status().isCreated());
    mvc.perform(multipart("/api/demandas/" + id + "/comentarios")
      .file(new MockMultipartFile("imagem", "comentario.png", "image/png", imageBytes.toByteArray()))
      .param("texto", "Olhar este print").with(user("felipe")).with(csrf())).andExpect(status().isCreated());
    long attachmentId = jdbc.queryForObject("SELECT id FROM anexo WHERE demanda_id = ?", Long.class, id);
    long commentId = jdbc.queryForObject("SELECT id FROM comentario WHERE demanda_id = ?", Long.class, id);
    long commentImageId = jdbc.queryForObject("SELECT id FROM comentario_imagem WHERE comentario_id = ?", Long.class, commentId);
    mvc.perform(get("/api/ia/demandas/" + id + "/imagens/" + attachmentId)
      .header("Authorization", "Bearer test-hub-ai-token")).andExpect(status().isOk());
    mvc.perform(get("/api/ia/demandas/" + id + "/comentarios/" + commentId + "/imagens/" + commentImageId)
      .header("Authorization", "Bearer test-hub-ai-token")).andExpect(status().isOk());
    mvc.perform(get("/api/ia/demandas").header("Authorization", "Bearer test-hub-ai-token"))
      .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id))
      .andExpect(jsonPath("$[0].descricao").value("<p>Detalhes</p>"))
      .andExpect(jsonPath("$[0].imagens[0].nome").value("descricao.png"))
      .andExpect(jsonPath("$[0].comentarios[0].texto").value("Olhar este print"))
      .andExpect(jsonPath("$[0].comentarios[0].imagens[0].nome").value("comentario.png"))
      .andExpect(jsonPath("$[1].id").value(secondId));
    mvc.perform(post("/api/ia/demandas/" + id + "/iniciar").header("Authorization", "Bearer test-hub-ai-token"))
      .andExpect(status().isOk()).andExpect(jsonPath("$.iaEstado").value("EM_EXECUCAO"));
    mvc.perform(post("/api/ia/demandas/" + id + "/comentarios")
      .header("Authorization", "Bearer test-hub-ai-token").contentType(MediaType.APPLICATION_JSON)
      .content("{\"texto\":\"Alterei o fluxo; testar o cadastro.\"}"))
      .andExpect(status().isOk()).andExpect(jsonPath("$.autor").value("Zyven"));
    mvc.perform(get("/api/ia/demandas").header("Authorization", "Bearer test-hub-ai-token"))
      .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(secondId));
    mvc.perform(post("/api/ia/demandas/" + id + "/resultado")
      .header("Authorization", "Bearer test-hub-ai-token").contentType(MediaType.APPLICATION_JSON)
      .content("{\"relatorio\":\"Implementado localmente; aguardando revisão.\"}"))
      .andExpect(status().isOk()).andExpect(jsonPath("$.iaEstado").value("AGUARDANDO_REVISAO"))
      .andExpect(jsonPath("$.status").value("DESENVOLVIMENTO_EM_PROGRESSO"))
      .andExpect(jsonPath("$.iaResultado").value("Implementado localmente; aguardando revisão."));
    mvc.perform(post("/api/ia/demandas/" + id + "/enviar-teste")
      .header("Authorization", "Bearer test-hub-ai-token"))
      .andExpect(status().isOk()).andExpect(jsonPath("$.iaEstado").value("CONCLUIDA"))
      .andExpect(jsonPath("$.status").value("EM_TESTE"))
      .andExpect(jsonPath("$.responsavel").value("Felipe"));
  }

  @Test void notificaTodosOsUsuariosConectadosAposCriarDemanda() throws Exception {
    mvc.perform(get("/api/demandas/eventos")).andExpect(status().isUnauthorized());
    MvcResult arthur = mvc.perform(get("/api/demandas/eventos").with(user("arthur")))
      .andExpect(status().isOk()).andExpect(request().asyncStarted()).andReturn();
    MvcResult felipe = mvc.perform(get("/api/demandas/eventos").with(user("felipe")))
      .andExpect(status().isOk()).andExpect(request().asyncStarted()).andReturn();
    try {
      mvc.perform(post("/api/demandas").with(user("felipe")).with(csrf())
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"titulo\":\"Nova pendência em tempo real\"}"))
        .andExpect(status().isCreated());
      for (MvcResult stream : new MvcResult[] {arthur, felipe}) {
        String body = stream.getResponse().getContentAsString();
        assertTrue(body.contains("event:demand"), body);
        assertTrue(body.contains("\"kind\":\"created\""), body);
        assertTrue(body.contains("\"actor\":\"felipe\""), body);
      }
    } finally {
      arthur.getRequest().getAsyncContext().complete();
      felipe.getRequest().getAsyncContext().complete();
    }
  }

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
    mvc.perform(get("/api/ia/demandas/panorama").header("Authorization", "Bearer test-hub-ai-token"))
      .andExpect(status().isOk()).andExpect(jsonPath("$.porStatus.ENCERRADA").value(1));
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
