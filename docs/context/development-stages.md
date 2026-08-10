# Watchwise

Sequência recomendada de desenvolvimento — Entity → Repository → Service → Controller

Ordem de implementação baseada nas dependências de chave estrangeira do modelo lógico e nos
endpoints do OpenAPI da aplicação. A lógica geral: primeiro as entidades "raiz" sem dependências,
depois quem depende só delas, e por último quem depende de várias outras entidades (Log, Curtida) ou
agrega tudo (Busca, Resumo).

## Fase 1 — Fundação — sem dependências de FK

| Entidade | Sequência |
|---|---|
| **Usuario** | entity → repository → service → service test → controller (registro, login, oauth, me, patch) → controller test |
| **Conteudo** | entity → repository → service → service test → controller (`/conteudos/referencia`) → controller test |

*Por quê primeiro: quase toda tabela do modelo tem FK para USUARIO ou CONTEUDO. Sem essas duas, nada mais compila de verdade.*

## Fase 1.5 — Auth hardening — fecha o módulo Auth antes de seguir para a Fase 2

O módulo Auth "sai de graça" junto com Usuario (Fase 1), já que login/registro só manipulam essa
entidade — mas nenhuma fase seguinte volta a mexer em `JwtService`/`RefreshToken`; elas só consomem
"o usuário está autenticado" como dado pronto. Por isso este é o único ponto do roteiro em que faz
sentido fechar essas pontas: feito depois, cada item aqui vira retrabalho espalhado pelas fases já
construídas.

| Item | O que fazer |
|---|---|
| **Conta habilitada / verificação de e-mail** | Campo `enabled`/`emailVerified` na entidade `Usuario` (migration + login recusando/limitando acesso se não verificado). Mexe na entidade — precisa entrar antes de outras fases dependerem dela. |
| **`/auth/oauth/{provider}`** | Já está listado no controller de Usuario da Fase 1 ("registro, login, oauth, me, patch") — hoje só falta implementar. Não é uma etapa nova, é uma lacuna dentro da própria Fase 1. Integrar Spring Security OAuth2 Client (Google) ao mecanismo de cookies/refresh já existente. |
| **Tirar `email` do payload do JWT** | O token carrega o e-mail como claim — PII desnecessária (não criptografado, só assinado). Manter só `sub` (userId) e buscar o e-mail no banco quando precisar. Ajuste em infraestrutura compartilhada por todas as fases seguintes — mais barato agora do que depois. |
| **Detecção de reuso de refresh token** | Hoje, reapresentar um refresh token já revogado só devolve 401 — nada acontece com o resto da sessão. Ao detectar reuso de um token já revogado, revogar todos os refresh tokens daquele usuário (assume conta comprometida). |
| **Logout em todos os dispositivos** | Endpoint autenticado (ex. `POST /auth/logout-all`) que revoga todos os refresh tokens do usuário — resposta manual a vazamento e consequência natural do item anterior. |
| **Rate limiting / proteção de força bruta em `/auth/login`** | Não existe limite de tentativas hoje — vulnerável a credential stuffing. Contador por IP+identifier com bloqueio temporário, ou lib tipo Bucket4j. |
| **Limpeza de refresh tokens expirados/revogados** | Não há rotina de purge — a tabela `refresh_tokens` cresce indefinidamente. `@Scheduled` simples (ou job de infra) apagando linhas com `expires_at` no passado. |

## Fase 2 — Dependem só de Usuario

| Entidade | Sequência |
|---|---|
| **Seguidor** | repository → service → service test → controller (seguidores, seguindo, seguir) → controller test |
| **SeguePessoa** | repository → service → service test → controller (`seguir-pessoas/{pessoaTmdbId}`) → controller test |

*Não têm FK própria para outra entidade além de Usuário — dá para implementar logo após a Fase 1 sem bloquear nada. Podem entrar no mesmo módulo de Usuário.*

## Fase 3 — Dependem de Usuario + Conteudo

| Entidade | Sequência |
|---|---|
| **Comentario** | entity → repository → service → service test → controller (`/conteudos/{id}/comentarios`, delete) → controller test |
| **Avaliacao** | entity → repository → service → service test → controller (`/conteudos/{id}/avaliacoes`, delete) → controller test |
| **Top5** | repository → service → service test → controller (`/usuarios/{id}/top5`) → controller test |

*Ordem entre essas três é livre — nenhuma depende da outra.*

**Antes do delete de Comentario/Avaliacao — autorização por dono do recurso**: este é o primeiro
ponto do roteiro onde existe um recurso "de alguém" pra proteger, então "só o dono pode apagar" deixa
de ser trivial. Decidir e padronizar aqui como validar dono do recurso (nível de serviço, como já é
feito em `getUserById`, ou `@PreAuthorize` com um security bean) — esse padrão vai se repetir em
Lista, ItemLista, Log e Curtida.

## Fase 4 — Dependem de Usuario (e Conteudo via itens)

| Entidade | Sequência |
|---|---|
| **Lista** | entity → repository → service → service test → controller (CRUD de lista) → controller test |
| **ItemLista** | entity → repository → service → service test → controller (`/listas/{id}/itens`) → controller test |

*ItemLista depende de Lista já existir, por isso vem logo depois dela e não junto com a Fase 3.*

## Fase 5 — Dependem de Usuario + Conteudo + Comentario + Avaliacao

| Entidade | Sequência |
|---|---|
| **Log (Diario)** | entity → repository → service → service test → controller (`/diario`, `/usuarios/{id}/diario`) → controller test |

*LOG tem FK opcional para COMENTARIO e AVALIACAO, então só faz sentido implementar depois que essas duas já existem.*

## Fase 6 — Depende de Usuario + Comentario + Log

| Entidade | Sequência |
|---|---|
| **Curtida** | entity → repository → service → service test → controller (curtir comentário, curtir log/diário) → controller test |

*É a entidade mais "tardia" do grafo: precisa de Comentario e Log prontos.*

## Fase 7 — Satélite — depende de Usuario + Conteudo

| Entidade | Sequência |
|---|---|
| **Notificacao** | entity → repository → service → service test → controller (`/notificacoes`) → controller test |

## Fase 8 — Agregações — sem entidade nova

| Serviço | Sequência |
|---|---|
| **Resumo** | service (agrega Log + Conteudo) → service test → controller (`/usuarios/{id}/resumo`) → controller test |
| **Busca** | service (agrega Lista, Usuario local + proxy TMDB) → service test → controller (`/busca`) → controller test |

*Deixe por último porque não são entidades novas — são serviços de leitura que combinam tudo que já foi construído, incluindo a chamada externa ao TMDB.*

---

## Por que essa ordem otimiza seu tempo

- Você nunca implementa um repository/service que referencia uma FK para uma entidade que ainda não existe (evita mocks provisórios e retrabalho).
- O módulo Auth sai "de graça" junto com Usuario, já que os endpoints de login/registro só manipulam essa entidade — e a Fase 1.5 garante que ele fica fechado antes de virar dependência silenciosa de todo o resto.
- Autorização por dono do recurso nasce na Fase 3 (primeiro recurso "de alguém" a proteger) e se repete como padrão já decidido nas fases seguintes, em vez de ser reinventada a cada entidade nova.
- Busca e Resumo ficam por último porque são leitura pura sobre dados que só existem depois que tudo mais estiver populado — testá-los antes seria testar contra um banco vazio.