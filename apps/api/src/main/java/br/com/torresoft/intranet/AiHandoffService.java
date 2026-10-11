package br.com.torresoft.intranet;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class AiHandoffService {
  private final JdbcTemplate jdbc;
  private final DemandEvents events;
  AiHandoffService(JdbcTemplate jdbc, DemandEvents events) { this.jdbc = jdbc; this.events = events; }

  @Transactional
  void sendToTest(long id, String suppliedSummary, String actor) {
    Long felipeId = jdbc.queryForObject("SELECT id FROM usuario WHERE login = 'felipe' AND ativo", Long.class);
    int changed = jdbc.update("""
      UPDATE demanda SET ia_estado = 'CONCLUIDA', status = 'EM_TESTE',
        responsavel_id = ?, atualizado_em = CURRENT_TIMESTAMP, versao = versao + 1
      WHERE id = ? AND destinada_ia AND ia_estado = 'AGUARDANDO_REVISAO'
      """, felipeId, id);
    if (changed == 0) {
      boolean alreadySent = jdbc.query("""
        SELECT ia_estado = 'CONCLUIDA' AND status = 'EM_TESTE' AS enviada
        FROM demanda WHERE id = ?
        """, (rs, row) -> rs.getBoolean("enviada"), id).stream().findFirst().orElse(false);
      if (alreadySent) return;
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Demanda não está aguardando revisão da IA");
    }
    String title = jdbc.queryForObject("SELECT titulo FROM demanda WHERE id = ?", String.class, id);
    String summary = suppliedSummary == null || suppliedSummary.isBlank()
      ? "A melhoria “" + title + "” está pronta para teste. Confira na aplicação se o fluxo e as informações atendem ao combinado."
      : suppliedSummary.trim();
    if (summary.length() > 500) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Resumo deve ter até 500 caracteres");
    jdbc.update("""
      INSERT INTO comentario(demanda_id, autor_id, texto, visibilidade, resumo_teste_ia)
      SELECT ?, id, ?, 'GERAL', TRUE FROM usuario WHERE login = 'arthur' AND ativo
      ON CONFLICT DO NOTHING
      """, id, summary);
    events.publishAfterCommit("updated", List.of(id), actor);
  }
}
