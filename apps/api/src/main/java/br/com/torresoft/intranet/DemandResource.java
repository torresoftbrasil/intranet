package br.com.torresoft.intranet;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class DemandResource {
  private static final List<String> STATUSES = List.of("AGUARDANDO_DESENVOLVIMENTO", "EM_DESENVOLVIMENTO", "DESENVOLVIMENTO_EM_PROGRESSO", "EM_TESTE", "REABERTA");
  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate named;
  DemandResource(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) { this.jdbc = jdbc; this.named = named; }

  record DemandInput(@NotBlank @Size(max=180) String titulo, String descricao, String status, Long responsavelId) {}
  record BulkInput(@NotEmpty List<Long> ids, String status, Long responsavelId, boolean alterarResponsavel) {}
  record FilterInput(@NotBlank @Size(max=80) String nome, String texto, String status, Long responsavelId) {}

  private long userId(Principal principal) {
    return jdbc.queryForObject("SELECT id FROM usuario WHERE login = ?", Long.class, principal.getName());
  }
  private String status(String value) {
    if (value == null || !STATUSES.contains(value)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Status inválido");
    return value;
  }
  private void responsible(Long id) {
    if (id != null && jdbc.queryForObject("SELECT count(*) FROM usuario WHERE id = ? AND ativo", Integer.class, id) == 0)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Responsável inválido");
  }
  private Map<String,Object> getDemand(long id) {
    return jdbc.query("""
      SELECT d.id, d.titulo, d.descricao, d.status, d.responsavel_id AS "responsavelId",
        u.nome AS responsavel, d.criado_em AS "criadoEm", d.atualizado_em AS "atualizadoEm", d.versao
      FROM demanda d LEFT JOIN usuario u ON u.id = d.responsavel_id WHERE d.id = ?
      """, (rs, row) -> Map.<String,Object>of(
        "id", rs.getLong("id"), "titulo", rs.getString("titulo"), "descricao", rs.getString("descricao"),
        "status", rs.getString("status"), "responsavelId", rs.getObject("responsavelId") == null ? "" : rs.getLong("responsavelId"),
        "responsavel", rs.getString("responsavel") == null ? "" : rs.getString("responsavel"),
        "criadoEm", rs.getObject("criadoEm", OffsetDateTime.class).toString(),
        "atualizadoEm", rs.getObject("atualizadoEm", OffsetDateTime.class).toString(), "versao", rs.getLong("versao")), id)
      .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Demanda não encontrada"));
  }

  @GetMapping("/usuarios") List<Map<String,Object>> users() {
    return jdbc.queryForList("SELECT id, login, nome FROM usuario WHERE ativo ORDER BY nome");
  }

  @GetMapping("/demandas") List<Map<String,Object>> list(@RequestParam(required=false) String texto,
      @RequestParam(required=false) String status, @RequestParam(required=false) Long responsavelId) {
    if (status != null && !status.isBlank()) status(status);
    StringBuilder sql = new StringBuilder("""
      SELECT d.id, d.titulo, d.status, d.responsavel_id AS "responsavelId", u.nome AS responsavel,
        d.criado_em AS "criadoEm", d.atualizado_em AS "atualizadoEm"
      FROM demanda d LEFT JOIN usuario u ON u.id = d.responsavel_id WHERE 1=1
      """);
    MapSqlParameterSource params = new MapSqlParameterSource();
    if (texto != null && !texto.isBlank()) { sql.append(" AND (lower(d.titulo) LIKE :texto OR lower(d.descricao) LIKE :texto)"); params.addValue("texto", "%" + texto.toLowerCase().trim() + "%"); }
    if (status != null && !status.isBlank()) { sql.append(" AND d.status = :status"); params.addValue("status", status); }
    if (responsavelId != null) { sql.append(" AND d.responsavel_id = :responsavel"); params.addValue("responsavel", responsavelId); }
    sql.append(" ORDER BY d.atualizado_em DESC, d.id DESC LIMIT 500");
    return named.queryForList(sql.toString(), params);
  }

  @GetMapping("/demandas/{id}") Map<String,Object> one(@PathVariable long id) { return getDemand(id); }

  @PostMapping("/demandas") @ResponseStatus(HttpStatus.CREATED) @Transactional
  Map<String,Object> create(@Valid @RequestBody DemandInput input, Principal principal) {
    responsible(input.responsavelId());
    String value = input.status() == null ? STATUSES.getFirst() : status(input.status());
    Long id = jdbc.queryForObject("""
      INSERT INTO demanda(titulo, descricao, status, responsavel_id, criado_por_id)
      VALUES (?, ?, ?, ?, ?) RETURNING id
      """, Long.class, input.titulo().trim(), input.descricao() == null ? "" : input.descricao(), value,
      input.responsavelId(), userId(principal));
    return getDemand(id);
  }

  @PutMapping("/demandas/{id}") @Transactional
  Map<String,Object> update(@PathVariable long id, @Valid @RequestBody DemandInput input) {
    getDemand(id); responsible(input.responsavelId());
    jdbc.update("""
      UPDATE demanda SET titulo = ?, descricao = ?, status = ?, responsavel_id = ?,
        atualizado_em = CURRENT_TIMESTAMP, versao = versao + 1 WHERE id = ?
      """, input.titulo().trim(), input.descricao() == null ? "" : input.descricao(), status(input.status()), input.responsavelId(), id);
    return getDemand(id);
  }

  @PostMapping("/demandas/lote") @Transactional
  Map<String,Integer> bulk(@Valid @RequestBody BulkInput input) {
    if (input.ids().size() > 500 || input.ids().stream().anyMatch(id -> id == null || id < 1))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Seleção inválida");
    if (input.status() == null && !input.alterarResponsavel())
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escolha uma alteração");
    if (input.status() != null) status(input.status());
    if (input.alterarResponsavel()) responsible(input.responsavelId());
    MapSqlParameterSource params = new MapSqlParameterSource().addValue("ids", input.ids()).addValue("status", input.status()).addValue("responsavel", input.responsavelId());
    Integer count = named.queryForObject("SELECT count(*) FROM demanda WHERE id IN (:ids)", params, Integer.class);
    if (count == null || count != input.ids().stream().distinct().count()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Seleção contém demanda inexistente");
    String set = (input.status() != null ? "status = :status, " : "") + (input.alterarResponsavel() ? "responsavel_id = :responsavel, " : "") + "atualizado_em = CURRENT_TIMESTAMP, versao = versao + 1";
    int updated = named.update("UPDATE demanda SET " + set + " WHERE id IN (:ids)", params);
    return Map.of("alteradas", updated);
  }

  @GetMapping("/filtros") List<Map<String,Object>> filters(Principal principal) {
    return jdbc.queryForList("SELECT id, nome, texto, status, responsavel_id AS \"responsavelId\" FROM filtro WHERE usuario_id = ? ORDER BY nome", userId(principal));
  }

  @PostMapping("/filtros") @ResponseStatus(HttpStatus.CREATED)
  Map<String,Object> saveFilter(@Valid @RequestBody FilterInput input, Principal principal) {
    if (input.status() != null && !input.status().isBlank()) status(input.status());
    responsible(input.responsavelId());
    Long id = jdbc.queryForObject("""
      INSERT INTO filtro(usuario_id, nome, texto, status, responsavel_id) VALUES (?, ?, ?, ?, ?)
      ON CONFLICT (usuario_id, nome) DO UPDATE SET texto = EXCLUDED.texto, status = EXCLUDED.status, responsavel_id = EXCLUDED.responsavel_id
      RETURNING id
      """, Long.class, userId(principal), input.nome().trim(), input.texto(), input.status(), input.responsavelId());
    return Map.of("id", id);
  }

  @DeleteMapping("/filtros/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
  void deleteFilter(@PathVariable long id, Principal principal) {
    jdbc.update("DELETE FROM filtro WHERE id = ? AND usuario_id = ?", id, userId(principal));
  }
}
