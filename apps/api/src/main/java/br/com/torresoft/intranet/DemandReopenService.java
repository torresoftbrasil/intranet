package br.com.torresoft.intranet;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class DemandReopenService {
  private final JdbcTemplate jdbc;
  private final DemandEvents events;

  DemandReopenService(JdbcTemplate jdbc, DemandEvents events) {
    this.jdbc = jdbc;
    this.events = events;
  }

  @Transactional
  void reopen(long id, String actor) {
    int changed = jdbc.update("""
      UPDATE demanda SET reaberta = TRUE, status = 'AGUARDANDO_DESENVOLVIMENTO',
        responsavel_id = CASE WHEN destinada_ia THEN (SELECT id FROM usuario WHERE login = 'zyven')
          ELSE (SELECT id FROM usuario WHERE login = 'arthur') END,
        ia_estado = CASE WHEN destinada_ia THEN 'PENDENTE' ELSE ia_estado END,
        ia_resultado = CASE WHEN destinada_ia THEN NULL ELSE ia_resultado END,
        ia_reservada_em = NULL, ia_concluida_em = NULL,
        atualizado_em = CURRENT_TIMESTAMP, versao = versao + 1
      WHERE id = ? AND status = 'EM_TESTE'
      """, id);
    if (changed == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Somente demandas em teste podem ser reabertas");
    // Cada ciclo de teste recebe seu próprio resumo público de Arthur.
    jdbc.update("UPDATE comentario SET resumo_teste_ia = FALSE WHERE demanda_id = ? AND resumo_teste_ia", id);
    events.publishAfterCommit("updated", List.of(id), actor);
  }
}
