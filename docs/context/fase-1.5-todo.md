# Fase 1.5 — Auth Hardening — To-do

Checklist de implementação da Fase 1.5 (`development-stages.md`), a ser fechada **antes** da Fase 2 (Seguidor/SeguePessoa) — nenhuma fase seguinte volta a mexer em `JwtService`/`RefreshToken`, então fechar essas pontas agora evita retrabalho espalhado depois.

Ordem sugerida: item 1 → item 2 (o campo do item 1 é pré-requisito do OAuth) → itens 3–7, que são independentes entre si e podem ser feitos em qualquer ordem entre eles.

---

## 1. Conta habilitada / verificação de e-mail

**Decisão tomada:** opção A por enquanto — campo pronto e checagem de login já implementada, mas cadastro comum nasce sempre verificado (`true`). Verificação de e-mail de verdade (mandar link por e-mail) fica pro upgrade descrito na seção "Upgrade futuro" abaixo.

- [ ] Migration `V4__add-email-verified-to-users.sql` — coluna `is_email_verified` (booleano) na tabela `users`, valor padrão `true`.
- [ ] Entidade `User` — novo campo `isEmailVerified`.
- [ ] `UserMapper` — decidir e ajustar onde o campo aparece (provavelmente ignorado nos DTOs de entrada, não exposto no `UserResponseDTO` público).
- [ ] `UserServiceImpl.saveNewUser` — define `isEmailVerified = true` no cadastro comum.
- [ ] `UserServiceImpl.login` — recusa login se `isEmailVerified == false` (não dispara de verdade enquanto o valor padrão for sempre `true`, mas evita ter que mexer no `login` de novo quando o upgrade futuro chegar).
- [ ] Testes de service — valor padrão aplicado no cadastro; login continua funcionando normalmente com o padrão `true`.

---

## 2. `/auth/oauth/{provider}`

**Decisões em aberto (preciso da sua resposta antes de codar):**
- Qual lib usar pra validar o token do Google (ex: `google-api-client`)?
- Regra de "e-mail já existe numa conta com senha": loga direto, bloqueia, ou outra regra?

**Etapas:**
- [ ] Escolher e adicionar a lib de validação do token do Google.
- [ ] Migration pra `User.password` aceitar nulo (conta OAuth não tem senha local).
- [ ] Decidir e implementar a regra de e-mail já existente (ver decisão em aberto acima).
- [ ] Método novo no `UserService` (busca ou cria usuário a partir do e-mail já validado) — conta criada aqui sempre nasce com `isEmailVerified = true`, já que o Google já verificou o e-mail.
- [ ] Endpoint novo no `AuthController` (`POST /auth/oauth/{provider}`), reaproveitando cookies/CSRF do `login` normal. Atualizar `openapi.yaml` marcando o endpoint como implementado (já está documentado, só falta o código).
- [ ] Testes de service, controller (unitário) e integração (com token mockado, já que não dá pra chamar o Google de verdade).

---

## Upgrade futuro (opcional) — verificação de e-mail de verdade

Não é uma fase separada da doc — é a versão mais completa do item 1, pra fazer quando/se decidir. A coluna `is_email_verified` e a checagem no `login` já existem desde o item 1, então esse upgrade é cirúrgico, não do zero.

- [ ] Adicionar dependência de e-mail (`spring-boot-starter-mail`) + configurar um provedor de verdade (conta/credencial externa).
- [ ] Escolher e implementar o mecanismo de token de verificação (tabela nova tipo `RefreshToken`, ou JWT com `TokenType.EMAIL_VERIFICATION`).
- [ ] Migration nova, só se for tabela de tokens (contas antigas continuam `true` pra sempre — a regra nova vale só pra quem cadastrar dali pra frente).
- [ ] Endpoint novo de confirmação (`.../verify-email`) + atualizar `openapi.yaml`.
- [ ] Trocar `UserServiceImpl.saveNewUser` pra setar `isEmailVerified = false` no cadastro comum (cadastro via OAuth continua `true`, sem mudança) e disparar o envio do e-mail com o token.
- [ ] Decidir sobre endpoint de reenvio e envio síncrono vs assíncrono (não travar a resposta do cadastro esperando o e-mail sair).
- [ ] Testes: login recusado quando não verificado (agora com efeito real), fluxo de confirmação, envio de e-mail interceptado em teste.

---

## 3. Tirar e-mail do payload do JWT

Hoje `JwtService.generateToken(userId, email, type)` embute `.claim("email", email)` no token — PII desnecessária, já que nada no backend lê esse claim (`JwtCookieAuthenticationFilter` só usa o `sub`/userId). Como o cookie é httpOnly, o frontend também não consegue ler esse claim, então ele não serve pra nada hoje.

- [ ] `JwtService.generateToken` — remover o parâmetro `email` e a linha `.claim("email", email)`.
- [ ] `RefreshTokenService`/`RefreshTokenServiceImpl` — `issueRefreshToken` e `rotateRefreshToken` param `email` fica sem uso (só existia pra repassar ao `generateToken`); remover da assinatura.
- [ ] `AuthController` — ajustar `buildAccessTokenCookie`/`buildRefreshTokenCookie` e as chamadas a `refreshTokenService.issueRefreshToken(...)` que hoje passam `user.email()`.
- [ ] Atualizar todos os testes que hoje stubam/verificam essas assinaturas com `email` (`JwtServiceTest`, `RefreshTokenServiceImplTest`, `AuthControllerTest`, `AuthControllerIntegrationTest`).

---

## 4. Detecção de reuso de refresh token

Hoje, em `RefreshTokenServiceImpl.rotateRefreshToken`, reapresentar um token já revogado (`storedToken.getRevoked() == true`) só lança `UnauthorizedException` — nada mais acontece. Reuso de um token revogado é sinal de que ele vazou (alguém guardou uma cópia de um token antigo e está tentando usá-lo depois que o dono já girou pra um novo).

- [ ] `RefreshTokenRepository` — novo método, ex. `@Modifying @Query("update RefreshToken t set t.revoked = true where t.user.id = :userId and t.revoked = false")` (`revokeAllByUserId`).
- [ ] `RefreshTokenService`/`RefreshTokenServiceImpl` — novo método público `revokeAllRefreshTokens(UUID userId)`, usando o repository acima (esse método também é usado pelo item 5).
- [ ] `RefreshTokenServiceImpl.rotateRefreshToken` — quando `storedToken.getRevoked()` for `true`, chamar `revokeAllRefreshTokens(storedToken.getUser().getId())` antes de lançar `UnauthorizedException`.
- [ ] Testes: reuso de token revogado realmente revoga todos os outros tokens válidos daquele usuário (não só o reapresentado).

---

## 5. Logout em todos os dispositivos

Endpoint novo `POST /auth/logout-all`, autenticado, que revoga todos os refresh tokens do usuário logado — reaproveita o `revokeAllRefreshTokens` do item 4.

- [ ] Endpoint novo em `AuthController` — resolve o id do usuário atual via `SecurityContextHolder` (mesmo padrão de `UserController.getCurrentUserId()`), chama `refreshTokenService.revokeAllRefreshTokens(id)`, limpa os 3 cookies (access/refresh/csrf) igual ao `logout` atual.
- [ ] **`SecurityConfig`** — atenção aqui: hoje `/auth/**` é `permitAll` e está na lista de rotas com CSRF ignorado (`.ignoringRequestMatchers("/auth/**")`). `/auth/logout-all` precisa ser autenticado e continuar exigindo CSRF (é uma mutação de estado real), então precisa de uma regra mais específica declarada **antes** do `permitAll` genérico de `/auth/**` (mesma ordem "mais específico primeiro" que o projeto já usa pra `/users/me` vs `/users/{userId}`), e não pode ficar dentro do `.ignoringRequestMatchers("/auth/**")` — ou seja, também exige ajustar essa lista de exclusão de CSRF.
- [ ] Atualizar `openapi.yaml` com o novo endpoint (endpoint novo, não documentado ainda).
- [ ] Testes: revoga todos os tokens do usuário autenticado, não afeta tokens de outros usuários, exige access token válido (401 sem cookie) e CSRF (403 sem token).

---

## 6. Rate limiting / proteção de força bruta em `/auth/login`

Não existe limite de tentativas hoje — vulnerável a credential stuffing (tentar várias senhas em sequência).

- [ ] Adicionar lib de rate limiting (ex. Bucket4j) ou implementar contador simples em memória por IP + identifier.
- [ ] Definir os parâmetros: quantas tentativas, em quanto tempo, quanto tempo de bloqueio.
- [ ] Decidir onde aplicar: filtro antes do controller, ou dentro de `AuthController.login`/`UserServiceImpl.login`.
- [ ] Resposta quando bloqueado (provavelmente `429 Too Many Requests`, com `GlobalExceptionHandler` tratando uma exceção nova).
- [ ] Testes: bloqueia após N tentativas seguidas, libera depois do tempo de bloqueio, não bloqueia identifiers/IPs diferentes entre si.

---

## 7. Limpeza de refresh tokens expirados/revogados

Não há rotina de purge — a tabela `refresh_tokens` cresce indefinidamente.

- [ ] `RefreshTokenRepository` — novo método `@Modifying @Query("delete from RefreshToken t where t.expiresAt < :now or t.revoked = true")` (ou separar em dois métodos).
- [ ] Job novo (ex. `RefreshTokenCleanupJob` no pacote `auth`) com `@Scheduled` (cron, ex. diário) chamando o método acima.
- [ ] Habilitar `@EnableScheduling` em alguma classe de configuração (ainda não existe no projeto).
- [ ] Testes: linhas expiradas/revogadas são removidas; linhas válidas não são tocadas.
