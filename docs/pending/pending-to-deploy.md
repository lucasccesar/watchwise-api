# Pendências para deploy

Levantado em 2026-09-01, lendo o código atual (não só os docs) — `docs/context/development-stages.md`,
`docs/context/progress.md`, `src/main/resources/application-prod.properties`, `pom.xml`,
`SecurityConfig`, `.gitignore`, e busca por `.github/workflows/`/`Dockerfile` na raiz do repo.

---

## Bloqueante

### 1. 🔴 `application-prod.properties` é só um template comentado

**Arquivo:** `src/main/resources/application-prod.properties` (todas as ~90 linhas com `#`)

Nenhuma variável de produção está de fato ativa — URL/credenciais do banco, `app.jwt.secret`,
`app.tmdb.api-key`, `GOOGLE_OAUTH_CLIENT_ID`, `app.cors.allowed-origins`. Antes de qualquer deploy,
isso precisa virar config real, com todo segredo vindo de variável de ambiente/secret manager —
**nunca hardcoded no arquivo** (mesma preocupação do item já registrado em `to-fix.md` sobre o secret
do JWT em texto puro no `application-dev.properties`, mas ali foi risco aceito pra dev; em prod não é
opcional).

### 2. 🔴 Sem Dockerfile e sem CI/CD

Não existe `.github/workflows/`, nem `Dockerfile`, nem qualquer automação de build/deploy no repo.
Hoje o build e os testes só rodam manualmente (`mvnw.cmd clean package`). Sem isso, não há como
garantir que o que vai pro ar é exatamente o que passou nos testes, nem um processo repetível de
deploy.

### 3. 🔴 Sem Actuator / health-check

**Arquivo:** `pom.xml` — não tem `spring-boot-starter-actuator`

Nenhum `/health` ou métrica exposta. Um load balancer, orquestrador (k8s, ECS, etc.) ou pipeline de
deploy não tem como verificar liveness/readiness da aplicação depois de subir.

### 4. 🔴 `GET /search` não implementado

Única peça do roteiro documentado em `development-stages.md` (Fase 8, "Aggregations, no new entity")
que ainda falta — todo o resto (Fases 1-7: `User`, `Content`, `Follower`, `FollowedPerson`,
`Top5Entry`, `WatchlistEntry`, `DroppedEntry`, `DiaryEntry`, `UserList`, `UserListItem`, `Comment`,
`Like`, `Notification`) já está construído. `Summary`/`Feed` (resto da Fase 8) já existem.

---

## Recomendado antes de ir ao ar (não bloqueante)

### 5. 🟡 Nenhum rastro de `security-review` ter sido rodado

Vale rodar (skill `security-review` já disponível neste ambiente) antes do primeiro deploy público —
a API expõe upload de URL de imagem (`profilePicture`), autenticação por cookie httpOnly e um proxy
de chamada a terceiro (TMDB) que já guarda uma `api-key` só no servidor.

### 6. 🟡 Domínio de `profilePicture` sem allowlist/blocklist de host

Já documentado como pendência aberta em `development-stages.md`. Risco baixo (é só uma URL guardada,
nunca baixada/processada pelo servidor), mas fica registrado aqui como parte do checklist de
segurança pré-deploy.

### 7. 🟡 `spring.jpa.open-in-view` nunca desabilitado (já em `docs/pending/to-fix.md`, item 3)

Repetido aqui porque é relevante pro checklist de deploy: `application-prod.properties` tem a flag
comentada junto com o resto do template. Troca de zero risco — o app já mapeia entidade→DTO
inteiramente dentro de métodos `@Transactional`. Ao ativar o profile de prod de verdade, não esquecer
de descomentar `spring.jpa.open-in-view=false`, `show-sql=false` e `format_sql=false` (mesmo arquivo,
item 5 de `to-fix.md`).

**Atualização 2026-09-05:** `server.forward-headers-strategy=native` (mesmo arquivo) já foi descomentado
antecipadamente — corrigia item 3 (média severidade) de `docs/pending/audit-completa-2026-09-04.md`,
onde sua ausência tornava todo rate-limit por IP (`AttemptLockout`/`RequestThrottler`) um contador
global compartilhado atrás de um reverse proxy. Diferente das demais linhas do template, essa não
depende de segredo/variável de ambiente, então não havia motivo pra esperar o resto do arquivo virar
config real.

---

## Já está pronto (conferido, não precisa de nada)

- **Migrations:** 41 migrations Flyway sequenciais e consistentes, `ddl-auto=validate` já configurado
  no template de prod.
- **Rate limiting:** aplicado de forma ampla (`RequestThrottler`) — login, registro, oauth, refresh,
  delete/patch de conta, follow, content-reference, diary, dropped — não é só um endpoint isolado.
- **CORS:** já externalizado via `app.cors.allowed-origins` (variável de ambiente), não hardcoded.
- **Auth:** OAuth (`/auth/oauth/{provider}`), logout-all, detecção de reuso de refresh token — tudo
  implementado.

---

## Fora deste arquivo

Os itens de correção de código já mapeados (não relacionados a infraestrutura de deploy) continuam em
`docs/pending/to-fix.md` — não duplicados aqui, exceto o item 7 acima, citado porque tem uma ação
concreta de "não esquecer de descomentar" no momento do deploy.
