# Intranet Torresoft

Primeira versão de um espaço simples para Arthur e Felipe acompanharem demandas. Inclui login, criação e edição de demandas, descrição com texto e fotos, cinco etapas abertas e encerramento, responsável, ações em lote, filtros e painel com filtros pessoais salvos.

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

- **Tela inicial** cria a demanda sem abrir outro formulário: escreva o título e pressione Enter; ele aparece em uma caixa editável acima. Depois escreva os detalhes ou cole prints e pressione Enter novamente para salvar. Status e responsável ficam na barra inferior, iniciando em **Aguardando desenvolvimento** e **Arthur**. `Ctrl+K` abre a busca e os atalhos de navegação.
- **Kanban** reúne todas as pendências abertas, independentemente do responsável. Arraste os cards entre as baias para mudar o status. Ao mover para **Em teste**, o Hub sugere Felipe e abre um comentário que aceita prints colados. A lista geral continua com filtros e ações em lote. Alterações chegam em tempo real; novas demandas mostram um aviso discreto e tocam um som curto que pode ser desligado. Uma conferência a cada 30 segundos cobre interrupções do canal ao vivo.
- **Demanda aberta** mantém título, descrição, status e responsável. O botão **Encerrar demanda** retira o item do Kanban; ele continua na lista geral com a data de encerramento guardada no banco. Cole prints com **Ctrl+V** na descrição. Os comentários aparecem à direita, do mais recente para o mais antigo, com autor, data e imagens. O botão **+** adiciona um comentário. São aceitas imagens PNG/JPEG de até 10 MB cada.
- **Demandas para IA** podem ser marcadas no formulário ou pelo botão direito de um card no Kanban. Ao destinar, o Hub atribui a demanda a **Zyven**, move para **Desenvolvimento em progresso** e a coloca no fim da baia, com marcador visual. A API `/api/ia/demandas` entrega até cinco pendências mais antigas com descrição, imagens e comentários mediante token Bearer. `/api/ia/demandas/panorama` lista todas as demandas com paginação e totais por status para acompanhamento e gráficos. Ao receber o relatório, a demanda fica aguardando revisão; uma chamada separada, feita somente após a aprovação e publicação, move para **Em teste** com Felipe. O contrato de `curl` está em `docs/integracao-hub-ia.md` no repositório Engenize.
- **Painel** mostra contagens da lista exibida e seus filtros salvos. Ajuste os filtros no Painel ou na lista e salve a combinação com um nome. A navegação fica no menu do avatar.

## Pipeline e publicação

O Hub usa `hub.engenize.com.br` na VPS `2.25.109.102`, isolado da intranet já hospedada nela. O projeto Compose `engenize-hub` contém PostgreSQL, API e frontend. O banco e os uploads ficam em `/srv/torresoft-data/hub`; somente o frontend escuta em `127.0.0.1:8083`, atrás do Nginx com HTTPS. O checkout fica em `/opt/torresoft/apps/hub` e seu `.env` contém credenciais exclusivas, fora do Git. O procedimento operacional está em [deploy/hub-vps.md](deploy/hub-vps.md).

O workflow `.github/workflows/pipeline.yml` testa API e Angular, valida as imagens Docker e publica o SHA da `main` por SSH. Ele usa o secret `HUB_DEPLOY_SSH_KEY` e a variable `HUB_DEPLOY_KNOWN_HOSTS`. `deploy/release.sh` faz backup criptografado do PostgreSQL antes de atualizar, testa `/healthz` e `/api/csrf` e tenta restaurar código e banco em caso de falha.

## Limites desta primeira versão

A lista retorna até 500 demandas por consulta. O painel resume a lista filtrada atual. Os eventos em tempo real são distribuídos pela instância atual da API; múltiplas instâncias exigirão um canal compartilhado entre elas. Ainda não há histórico de alterações nem regras de permissão diferentes entre Arthur e Felipe.
