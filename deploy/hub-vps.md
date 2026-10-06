# Hub Engenize na VPS

## Isolamento

- Código: `/opt/torresoft/apps/hub`, repositório `torresoftbrasil/intranet`.
- Compose: `engenize-hub`, com PostgreSQL 18, API Java e frontend Angular.
- Dados: `/srv/torresoft-data/hub/postgres` e `/srv/torresoft-data/hub/uploads`.
- Backups antes de cada atualização: `/srv/torresoft-data/hub/backups`.
- Porta do frontend: `127.0.0.1:8083`; PostgreSQL e API ficam somente na rede Docker.
- Domínio: `hub.engenize.com.br`, terminado pelo Nginx do host com Let's Encrypt.

Não reutilizar o checkout, banco ou projeto Compose da intranet preexistente na VPS. Não executar `docker compose down -v` nem limpeza de volumes. O arquivo `.env` contém credenciais e não entra no Git.

## Primeira instalação

Ler `/opt/torresoft/README.md` e `/opt/torresoft/docs/{NOVA-APLICACAO,PERSISTENCIA,PORTAS}.md`. Reservar o domínio e a porta 8083 em `PORTAS.md`; o helper da plataforma só aceita `torresoftbrasil.com.br`, então a configuração deste domínio é manual.

```bash
git clone git@github.com:torresoftbrasil/intranet.git /opt/torresoft/apps/hub
install -d -m 750 /srv/torresoft-data/hub
install -d -o 999 -g 999 -m 700 /srv/torresoft-data/hub/postgres
install -d -o 10001 -g 10001 -m 750 /srv/torresoft-data/hub/uploads
install -d -m 700 /srv/torresoft-data/hub/backups
```

Criar `/opt/torresoft/apps/hub/.env` com permissão 600. Usar `DB_NAME=hub`, `DB_USER=hub`, senha aleatória exclusiva, `ARTHUR_INITIAL_PASSWORD` e `FELIPE_INITIAL_PASSWORD` distintos com pelo menos 12 caracteres, `SESSION_COOKIE_SECURE=true`, `HUB_DATA_DIR=/srv/torresoft-data/hub` e `WEB_PORT=8083`. As senhas iniciais só são usadas para criar contas ausentes. Guardá-las em gerenciador de senhas; não imprimir em logs ou versionar.

Validar `docker compose --env-file .env -f deploy/compose.yaml config`, iniciar com `up -d --build --wait` e conferir `/healthz`, `/api/csrf`, histórico Flyway e login. O primeiro start aplica as migrações versionadas no banco novo. Gerar `pg_dump -Fc`, checksum e verificar leitura com `pg_restore --list` antes de considerar o backup válido.

## DNS, Nginx e certificado

O registro A de `hub.engenize.com.br` deve apontar para `2.25.109.102`. Instalar primeiro `deploy/nginx-host-http.conf` em `/etc/nginx/sites-available/hub.engenize.com.br.conf`, habilitar com link `zz-hub.engenize.com.br.conf`, executar `nginx -t` e recarregar. Emitir certificado com `certbot certonly --webroot -w /var/www/letsencrypt -d hub.engenize.com.br`. Depois instalar `deploy/nginx-host.conf`, testar e recarregar o Nginx. O hook do Certbot deve testar e recarregar o Nginx após a renovação deste certificado. Conferir também os demais domínios do host.

## GitHub Actions

O workflow `.github/workflows/pipeline.yml` testa e publica cada push na `main`. Criar uma chave SSH exclusiva para o Actions e instalá-la no `authorized_keys` do root com `restrict,command="/usr/local/sbin/engenize-hub-deploy-ssh"`. Instalar `deploy/ssh-entrypoint.sh` nesse caminho com modo 755. A chave só aceita `deploy <SHA completo>` e o release exige o HEAD atual da `main`.

No repositório GitHub, configurar:

| Tipo | Nome | Valor |
| --- | --- | --- |
| Secret | `HUB_DEPLOY_SSH_KEY` | Chave privada exclusiva do Actions |
| Variable | `HUB_DEPLOY_KNOWN_HOSTS` | Chave de host Ed25519 verificada para `2.25.109.102` |

O script `deploy/release.sh` faz um dump validado antes de atualizar, constrói as imagens e testa a resposta do frontend e da API. Em falha após iniciar a nova versão, tenta restaurar banco e código anteriores. Conferir o resultado antes de repetir um deploy com falha.

## Verificação

```bash
docker compose --env-file .env -f deploy/compose.yaml ps
curl -fsS http://127.0.0.1:8083/healthz
curl -fsS http://127.0.0.1:8083/api/csrf
curl -fsS https://hub.engenize.com.br/healthz
```

Manter cópia criptografada fora da VPS dos dumps e uploads. Retenção e rotina externa devem ser definidas antes de depender dos backups locais como recuperação completa.
