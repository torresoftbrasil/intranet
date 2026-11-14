UPDATE demanda SET reaberta = FALSE
WHERE reaberta AND status IN ('EM_TESTE', 'ENCERRADA');
