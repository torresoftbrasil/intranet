ALTER TABLE demanda ADD COLUMN destinada_ia BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE demanda ADD COLUMN ia_estado VARCHAR(24);
ALTER TABLE demanda ADD COLUMN ia_resultado TEXT;
ALTER TABLE demanda ADD COLUMN ia_reservada_em TIMESTAMPTZ;
ALTER TABLE demanda ADD COLUMN ia_concluida_em TIMESTAMPTZ;
ALTER TABLE demanda ADD CONSTRAINT demanda_ia_estado_check CHECK (
  ia_estado IS NULL OR ia_estado IN ('PENDENTE', 'EM_EXECUCAO', 'AGUARDANDO_REVISAO', 'CONCLUIDA')
);
CREATE INDEX demanda_ia_fila_idx ON demanda(criado_em, id)
  WHERE destinada_ia AND ia_estado = 'PENDENTE' AND status <> 'ENCERRADA';
