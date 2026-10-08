package br.com.torresoft.intranet;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/ia/demandas")
public class AiDemandResource {
  private final JdbcTemplate jdbc;
  private final Path uploads;
  private final DemandEvents events;

  AiDemandResource(JdbcTemplate jdbc, DemandEvents events, @Value("${app.uploads-dir}") String uploadsDir) {
    this.jdbc = jdbc;
    this.events = events;
    this.uploads = Path.of(uploadsDir).toAbsolutePath().normalize();
  }

  record ResultInput(@NotBlank @Size(max = 20000) String relatorio) {}
  record CommentInput(@NotBlank @Size(max = 5000) String texto) {}

  private Map<String,Object> demand(long id) {
    return jdbc.query("""
      SELECT d.id, d.titulo, d.descricao, d.status, d.ia_estado, d.ia_resultado,
        d.criado_em, d.atualizado_em, u.nome AS responsavel
      FROM demanda d LEFT JOIN usuario u ON u.id = d.responsavel_id
      WHERE d.id = ?
      """, (rs, row) -> Map.<String,Object>ofEntries(
        Map.entry("id", rs.getLong("id")), Map.entry("titulo", rs.getString("titulo")),
        Map.entry("descricao", rs.getString("descricao")), Map.entry("status", rs.getString("status")),
        Map.entry("iaEstado", rs.getString("ia_estado") == null ? "" : rs.getString("ia_estado")),
        Map.entry("iaResultado", rs.getString("ia_resultado") == null ? "" : rs.getString("ia_resultado")),
        Map.entry("criadoEm", rs.getObject("criado_em", OffsetDateTime.class).toString()),
        Map.entry("atualizadoEm", rs.getObject("atualizado_em", OffsetDateTime.class).toString()),
        Map.entry("responsavel", rs.getString("responsavel") == null ? "" : rs.getString("responsavel")),
        Map.entry("imagens", jdbc.query("""
          SELECT id, nome, tipo FROM anexo WHERE demanda_id = ? ORDER BY id
          """, (image, index) -> Map.<String,Object>of(
            "id", image.getLong("id"), "nome", image.getString("nome"), "tipo", image.getString("tipo"),
            "url", "/api/ia/demandas/" + id + "/imagens/" + image.getLong("id")), id)),
        Map.entry("comentarios", jdbc.query("""
          SELECT c.id, c.texto, c.criado_em, u.nome AS autor
          FROM comentario c JOIN usuario u ON u.id = c.autor_id
          WHERE c.demanda_id = ? ORDER BY c.criado_em ASC, c.id ASC
          """, (comment, index) -> Map.<String,Object>of(
            "id", comment.getLong("id"), "texto", comment.getString("texto"),
            "autor", comment.getString("autor"),
            "criadoEm", comment.getObject("criado_em", OffsetDateTime.class).toString(),
            "imagens", jdbc.query("""
              SELECT id, nome, tipo FROM comentario_imagem WHERE comentario_id = ? ORDER BY id
              """, (image, imageIndex) -> Map.<String,Object>of(
                "id", image.getLong("id"), "nome", image.getString("nome"), "tipo", image.getString("tipo"),
                "url", "/api/ia/demandas/" + id + "/comentarios/" + comment.getLong("id") + "/imagens/" + image.getLong("id")),
              comment.getLong("id"))), id))
      ), id).stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Demanda não encontrada"));
  }

  @GetMapping List<Map<String,Object>> available(@RequestParam(defaultValue = "5") int limite) {
    if (limite < 1 || limite > 5) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Limite deve ser de 1 a 5");
    List<Long> ids = jdbc.queryForList("""
      SELECT id FROM demanda WHERE destinada_ia AND ia_estado = 'PENDENTE' AND status <> 'ENCERRADA'
      ORDER BY criado_em ASC, id ASC LIMIT ?
      """, Long.class, limite);
    return ids.stream().map(this::demand).toList();
  }

  @GetMapping("/panorama") Map<String,Object> overview(@RequestParam(defaultValue = "0") int pagina,
      @RequestParam(defaultValue = "500") int tamanho) {
    if (pagina < 0 || tamanho < 1 || tamanho > 500 || (long) pagina * tamanho > Integer.MAX_VALUE)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Paginação inválida");
    long total = jdbc.queryForObject("SELECT count(*) FROM demanda", Long.class);
    List<Map<String,Object>> items = jdbc.query("""
      SELECT d.id, d.titulo, d.status, d.destinada_ia, d.ia_estado, d.ia_resultado,
        d.criado_em, d.atualizado_em, d.encerrada_em, u.nome AS responsavel
      FROM demanda d LEFT JOIN usuario u ON u.id = d.responsavel_id
      ORDER BY d.criado_em ASC, d.id ASC LIMIT ? OFFSET ?
      """, (rs, row) -> Map.<String,Object>ofEntries(
        Map.entry("id", rs.getLong("id")), Map.entry("titulo", rs.getString("titulo")),
        Map.entry("status", rs.getString("status")), Map.entry("destinadaIa", rs.getBoolean("destinada_ia")),
        Map.entry("iaEstado", rs.getString("ia_estado") == null ? "" : rs.getString("ia_estado")),
        Map.entry("temResultadoIa", rs.getString("ia_resultado") != null),
        Map.entry("criadoEm", rs.getObject("criado_em", OffsetDateTime.class).toString()),
        Map.entry("atualizadoEm", rs.getObject("atualizado_em", OffsetDateTime.class).toString()),
        Map.entry("encerradaEm", rs.getObject("encerrada_em", OffsetDateTime.class) == null ? "" : rs.getObject("encerrada_em", OffsetDateTime.class).toString()),
        Map.entry("responsavel", rs.getString("responsavel") == null ? "" : rs.getString("responsavel"))),
      tamanho, (long) pagina * tamanho);
    Map<String,Long> byStatus = new LinkedHashMap<>();
    for (Map<String,Object> row : jdbc.queryForList("SELECT status, count(*) AS total FROM demanda GROUP BY status ORDER BY status"))
      byStatus.put((String) row.get("status"), ((Number) row.get("total")).longValue());
    Map<String,Long> byAiState = new LinkedHashMap<>();
    for (Map<String,Object> row : jdbc.queryForList("SELECT COALESCE(ia_estado, 'NAO_DESTINADA') AS estado, count(*) AS total FROM demanda GROUP BY estado ORDER BY estado"))
      byAiState.put((String) row.get("estado"), ((Number) row.get("total")).longValue());
    return Map.of("total", total, "pagina", pagina, "tamanho", tamanho,
      "totalPaginas", (total + tamanho - 1) / tamanho, "porStatus", byStatus,
      "porIaEstado", byAiState, "demandas", items);
  }

  @GetMapping("/{id}") Map<String,Object> one(@PathVariable long id) { return demand(id); }

  @PostMapping("/{id}/iniciar") @Transactional
  Map<String,Object> start(@PathVariable long id) {
    int changed = jdbc.update("""
      UPDATE demanda SET ia_estado = 'EM_EXECUCAO', ia_reservada_em = CURRENT_TIMESTAMP,
        atualizado_em = CURRENT_TIMESTAMP, versao = versao + 1
      WHERE id = ? AND destinada_ia AND ia_estado = 'PENDENTE' AND status <> 'ENCERRADA'
      """, id);
    if (changed == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Demanda indisponível para iniciar");
    events.publishAfterCommit("updated", List.of(id), "zyven");
    return demand(id);
  }

  @PostMapping("/{id}/resultado") @Transactional
  Map<String,Object> result(@PathVariable long id, @Valid @RequestBody ResultInput input) {
    int changed = jdbc.update("""
      UPDATE demanda SET ia_estado = 'AGUARDANDO_REVISAO', ia_resultado = ?,
        ia_concluida_em = CURRENT_TIMESTAMP, atualizado_em = CURRENT_TIMESTAMP, versao = versao + 1
      WHERE id = ? AND destinada_ia AND ia_estado = 'EM_EXECUCAO'
      """, input.relatorio().trim(), id);
    if (changed == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Demanda não está em execução pela IA");
    events.publishAfterCommit("updated", List.of(id), "zyven");
    return demand(id);
  }

  @PostMapping("/{id}/comentarios") @Transactional
  Map<String,Object> comment(@PathVariable long id, @Valid @RequestBody CommentInput input) {
    List<Long> created = jdbc.queryForList("""
      INSERT INTO comentario(demanda_id, autor_id, texto)
      SELECT d.id, u.id, ? FROM demanda d JOIN usuario u ON u.login = 'zyven' AND u.ativo
      WHERE d.id = ? AND d.destinada_ia AND d.ia_estado IN ('EM_EXECUCAO', 'AGUARDANDO_REVISAO')
      RETURNING id
      """, Long.class, input.texto().trim(), id);
    if (created.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Demanda não está em execução ou revisão pela IA");
    events.publishAfterCommit("updated", List.of(id), "zyven");
    return Map.of("id", created.getFirst(), "demandaId", id, "autor", "Zyven", "texto", input.texto().trim());
  }

  @PostMapping("/{id}/liberar") @Transactional
  Map<String,Object> release(@PathVariable long id) {
    int changed = jdbc.update("""
      UPDATE demanda SET ia_estado = 'PENDENTE', ia_reservada_em = NULL,
        atualizado_em = CURRENT_TIMESTAMP, versao = versao + 1
      WHERE id = ? AND destinada_ia AND ia_estado = 'EM_EXECUCAO'
      """, id);
    if (changed == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Demanda não está em execução pela IA");
    events.publishAfterCommit("updated", List.of(id), "zyven");
    return demand(id);
  }

  @PostMapping("/{id}/enviar-teste") @Transactional
  Map<String,Object> sendToTest(@PathVariable long id) {
    Long felipeId = jdbc.queryForObject("SELECT id FROM usuario WHERE login = 'felipe' AND ativo", Long.class);
    int changed = jdbc.update("""
      UPDATE demanda SET ia_estado = 'CONCLUIDA', status = 'EM_TESTE',
        responsavel_id = ?,
        atualizado_em = CURRENT_TIMESTAMP, versao = versao + 1
      WHERE id = ? AND destinada_ia AND ia_estado = 'AGUARDANDO_REVISAO'
      """, felipeId, id);
    if (changed == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Demanda não está aguardando revisão da IA");
    events.publishAfterCommit("updated", List.of(id), "zyven");
    return demand(id);
  }

  @GetMapping("/{id}/imagens/{imageId}")
  ResponseEntity<FileSystemResource> image(@PathVariable long id, @PathVariable long imageId) {
    var found = jdbc.query("""
      SELECT a.tipo, a.caminho FROM anexo a JOIN demanda d ON d.id = a.demanda_id
      WHERE a.id = ? AND a.demanda_id = ?
      """, (rs, row) -> Map.<String,String>of("tipo", rs.getString("tipo"), "caminho", rs.getString("caminho")),
      imageId, id).stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    return file(found);
  }

  @GetMapping("/{id}/comentarios/{commentId}/imagens/{imageId}")
  ResponseEntity<FileSystemResource> commentImage(@PathVariable long id, @PathVariable long commentId,
      @PathVariable long imageId) {
    var found = jdbc.query("""
      SELECT i.tipo, i.caminho FROM comentario_imagem i
      JOIN comentario c ON c.id = i.comentario_id
      JOIN demanda d ON d.id = c.demanda_id
      WHERE i.id = ? AND c.id = ? AND d.id = ?
      """, (rs, row) -> Map.<String,String>of("tipo", rs.getString("tipo"), "caminho", rs.getString("caminho")),
      imageId, commentId, id).stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    return file(found);
  }

  private ResponseEntity<FileSystemResource> file(Map<String,String> found) {
    Path path = uploads.resolve(found.get("caminho")).normalize();
    if (!path.startsWith(uploads) || !Files.isRegularFile(path)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    return ResponseEntity.ok().contentType(MediaType.parseMediaType(found.get("tipo")))
      .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
      .header("X-Content-Type-Options", "nosniff")
      .body(new FileSystemResource(path));
  }
}
