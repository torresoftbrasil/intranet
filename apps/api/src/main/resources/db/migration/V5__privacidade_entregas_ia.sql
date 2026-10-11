ALTER TABLE comentario
  ADD COLUMN visibilidade VARCHAR(24) NOT NULL DEFAULT 'GERAL',
  ADD COLUMN resumo_teste_ia BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE comentario
  ADD CONSTRAINT comentario_visibilidade_check
  CHECK (visibilidade IN ('GERAL', 'SOMENTE_ARTHUR'));

CREATE UNIQUE INDEX comentario_resumo_teste_ia_unico_idx
  ON comentario(demanda_id) WHERE resumo_teste_ia;

-- Comentários registrados pelo token da IA são notas internas para Arthur.
UPDATE comentario
SET visibilidade = 'SOMENTE_ARTHUR'
WHERE autor_id IN (SELECT id FROM usuario WHERE login = 'zyven');

-- As entregas já publicadas ganham um único recado de teste, escrito em linguagem de operação.
INSERT INTO comentario(demanda_id, autor_id, texto, visibilidade, resumo_teste_ia)
SELECT d.id, u.id, resumo.texto, 'GERAL', TRUE
FROM (VALUES
  (4, 'A lista de fornecedores agora mostra os dados principais logo no cabeçalho. Confira as colunas e abra um fornecedor para validar as informações.'),
  (5, 'A lista de insumos ganhou um cabeçalho mais claro. Confira se os dados aparecem corretamente ao buscar e abrir um insumo.'),
  (7, 'Orçamento e Gestão de Custos agora mostram etapas e subetapas de forma organizada. Navegue pela árvore e confira se os itens aparecem no grupo certo.'),
  (8, 'As listas de solicitações e quadros de concorrência estão disponíveis em Compras e Contratos. Confira os dados, filtros e a abertura de cada registro.'),
  (9, 'A lista de ordens de compra agora reúne as informações principais de cada ordem. Confira as colunas, os filtros e a abertura de uma ordem.'),
  (10, 'A lista de contratos agora mostra os dados principais para consulta. Confira os valores, a situação e a abertura de um contrato.'),
  (12, 'A lista de boletins de medição está disponível em Compras e Contratos. Confira os boletins, os filtros e a abertura de um registro.'),
  (13, 'A nova solicitação permite escolher itens do orçamento, informar quantidades e revisar antes de confirmar. Faça uma solicitação de teste e confira o resultado.'),
  (14, 'O quadro de concorrência agora conduz a seleção dos itens, fornecedores, propostas e vencedor. Faça um teste completo e confira o documento gerado.'),
  (15, 'A tela do contrato reúne os itens, valores, assinaturas, medições e ajustes. Confira a aprovação e as ações disponíveis em cada etapa.'),
  (16, 'Agora é possível criar um boletim de medição dentro do contrato e gerar seu PDF. Teste as quantidades, os valores e o documento final.'),
  (17, 'A tela de boletins de medição ganhou filtros para encontrar os registros. Confira a busca por projeto, fornecedor e período.'),
  (18, 'A Gestão de Custos agora reúne orçamento, compras, financeiro e projeção por item. Confira os totais dos grupos e ajuste uma projeção.'),
  (19, 'O Orçamento mostra o total de cada projeto e permite importar itens por planilha. Confira a estrutura e teste a importação em um projeto vazio.'),
  (20, 'A área de funcionários do fornecedor foi ajustada para facilitar a leitura e a edição. Abra um fornecedor e confira os dados em tela larga e estreita.'),
  (21, 'A lista de lançamentos reúne projeto, fornecedor, vencimento, origem e pagamento. Confira os filtros e os dados de alguns lançamentos.'),
  (22, 'Os relatórios de Custos e Compras estão separados e aceitam mais de um projeto. Confira os gráficos, os filtros e a justificativa de uma variação.'),
  (23, 'A Gestão de Custos permite encerrar o período e consultar meses anteriores. Teste o encerramento em um projeto de exemplo e confira o mês seguinte.')
) AS resumo(demanda_id, texto)
JOIN demanda d ON d.id = resumo.demanda_id AND d.destinada_ia
JOIN usuario u ON u.login = 'arthur'
WHERE d.status IN ('EM_TESTE', 'ENCERRADA')
ON CONFLICT DO NOTHING;
