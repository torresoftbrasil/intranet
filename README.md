# Intranet Torresoft

Primeira versão de um espaço simples para Arthur e Felipe acompanharem demandas. Inclui login, criação e edição de demandas, descrição com texto e fotos, cinco status, responsável, ações em lote, filtros e painel com filtros pessoais salvos.

## Stack

- Angular 22 no frontend, Java 25 e Spring Boot 4.1 na API, PostgreSQL 18.
- Flyway versiona o banco. Arquivos de imagem ficam em `data/uploads` no desenvolvimento e em um volume persistente no servidor; o banco guarda metadados e o nome do arquivo.
- A API usa sessão HTTP em cookie `HttpOnly` e proteção CSRF. As senhas são guardadas como hash BCrypt. As duas contas são criadas na primeira inicialização.

## Rodar localmente

1. Copie `.env.example` para `.env` e defina `DB_PASSWORD`, `ARTHUR_INITIAL_PASSWORD` e `FELIPE_INITIAL_PASSWORD` com senhas diferentes de pelo menos 12 caracteres. O arquivo `.env` não entra no Git.
2. Inicie o PostgreSQL com `make db-up`.
3. Em outro terminal, execute `make api-run`.
4. Instale o frontend com `cd apps/web && npm ci`; depois, na raiz, execute `make web-run`.
5. Abra `http://localhost:4200` e entre como `arthur` ou `felipe`.

Se a porta 4200 já estiver ocupada, use `cd apps/web && npm start -- --port 4201`. A API local responde em `http://localhost:8083`. As senhas iniciais são usadas somente ao criar as contas; alterá-las no `.env` depois não troca senhas existentes.

## Uso

- **Tela inicial** usa a barra central para começar uma demanda. Digite uma frase e pressione Enter para revisar título e responsável antes de salvar. `Buscar termo` filtra a lista; `Painel` e `Minhas demandas` navegam diretamente. O atalho `Ctrl+K` abre os mesmos comandos.
- **Demandas** aparecem em cards. Use **Filtros** para combinar texto, status e responsável. Marque vários cards para trocar status e/ou responsável de uma só vez.
- **Nova demanda** cria título, descrição, status e responsável. Na barra central ou na descrição, cole prints com **Ctrl+V**; também é possível usar **+ Imagem** ou **Inserir foto**. Uma prévia aparece antes de salvar, e as imagens são anexadas à demanda no salvamento. São aceitas imagens PNG/JPEG de até 10 MB cada.
- **Painel** mostra contagens da lista exibida e seus filtros salvos. Ajuste os filtros no Painel ou na lista e salve a combinação com um nome. A navegação fica no menu do avatar.

## Pipeline e publicação

O workflow `.github/workflows/pipeline.yml` compila API e Angular e valida as imagens Docker a cada push em `main`. A etapa SSH só executa quando forem configurados `INTRANET_DEPLOY_HOST`, `INTRANET_DEPLOY_PATH`, `INTRANET_DEPLOY_USER`, `INTRANET_DEPLOY_KNOWN_HOSTS` e o secret `INTRANET_DEPLOY_SSH_KEY`. Assim, a pipeline está pronta sem presumir domínio ou servidor para a intranet.

No servidor, mantenha um clone do repositório no caminho escolhido e um `.env` na raiz. Defina `SESSION_COOKIE_SECURE=true`, `WEB_PORT` se 8084 estiver ocupada e, se necessário, `INTRANET_DATA_DIR`. A composição de produção expõe o frontend só em `127.0.0.1`; configure HTTPS e o virtual host no proxy externo antes de dar acesso aos usuários. `deploy/release.sh` faz backup do PostgreSQL, publica o SHA validado, testa `/healthz` e `/api/csrf` e tenta voltar à versão e ao banco anteriores se houver falha. Os backups e uploads devem entrar na rotina de backup externo quando a intranet for para a nuvem.

## Limites desta primeira versão

A lista retorna até 500 demandas por consulta. O painel resume a lista filtrada atual. Ainda não há comentários, notificações, histórico de alterações nem regras de permissão diferentes entre Arthur e Felipe.
