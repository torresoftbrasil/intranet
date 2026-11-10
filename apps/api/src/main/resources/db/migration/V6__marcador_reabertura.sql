ALTER TABLE demanda ADD COLUMN reaberta BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX demanda_reaberta_idx ON demanda(id) WHERE reaberta;
