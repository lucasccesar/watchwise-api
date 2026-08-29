# Watchwise API

API REST do **Watchwise**, uma rede social para acompanhar, avaliar e comentar filmes, séries,
temporadas e episódios.

Filmes, séries, elenco e premiações **nunca são armazenados no banco** — essa informação sempre vem
da [API do TMDB](https://www.themoviedb.org/documentation/api). O backend guarda apenas uma
referência leve (`Content`: tipo + ids do TMDB) usada como chave interna para ligar as interações de
um usuário (comentários, avaliações, diário, listas, top5 etc.) a uma peça de mídia.

## Stack

- **Java 21** / **Spring Boot 4.1**
- **Spring Data JPA** + **PostgreSQL** (Testcontainers nos testes de repositório, Docker Compose no
  ambiente de desenvolvimento)
- **Flyway** para migrations
- **Spring Security** — autenticação stateless via JWT entregue em cookies `httpOnly`, com proteção
  CSRF (`XSRF-TOKEN` / `X-XSRF-TOKEN`)
- **MapStruct** para mapeamento entidade ↔ DTO
- **Lombok**
- **Maven** (via wrapper, sem necessidade de instalação global)

## Pré-requisitos

- JDK 21
- Docker (o Postgres do ambiente `dev` sobe automaticamente via `spring.docker.compose.enabled=true`)

## Como rodar

```bash
./mvnw spring-boot:run        # Linux/macOS
mvnw.cmd spring-boot:run       # Windows
```

A aplicação sobe em `http://localhost:8080/api/v1` (context path `/api/v1`), com o profile `dev`
ativo por padrão — o Postgres é iniciado automaticamente via Docker Compose
(`docker-compose.yaml`).

### Testes

```bash
mvnw.cmd test                                                                       # suíte completa
mvnw.cmd test "-Dtest=UserServiceImplTest"                                          # uma classe
mvnw.cmd test "-Dtest=UserServiceImplTest#shouldReturnUserResponseDtoWhenIdExists"  # um método
```

Testes de repositório (`*RepositoryTest`) usam Testcontainers e sobem um container real
`postgres:16-alpine` — o Docker precisa estar rodando.

### Build

```bash
mvnw.cmd clean package                # gera o jar (roda os testes)
mvnw.cmd clean package -DskipTests    # gera o jar sem rodar os testes
```

## Configuração

As propriedades de aplicação ficam em `src/main/resources`:

- `application.properties` — comuns a todos os profiles (context path, profile ativo)
- `application-dev.properties` — profile de desenvolvimento local (conexão com o Postgres do
  `docker-compose.yaml`, Flyway habilitado, `ddl-auto=validate`, log de SQL habilitado)
- `application-prod.properties` — template do profile de produção, com as variáveis de ambiente
  esperadas (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`, `CORS_ALLOWED_ORIGINS`,
  `GOOGLE_OAUTH_CLIENT_ID`, entre outras)

## Estrutura do projeto

Código organizado por feature em `com.watchwise.watchwise_api.<feature>`, cada uma dividida em
`dto/`, `entity/`, `mapper/`, `repository/`, `service/` (+ `service/impl/`) e `controller/`.
Assuntos transversais ficam em `common` (configuração de segurança, tratamento global de exceções,
rate limiting).

Fluxo de uma requisição: `Controller → Service (interface) → ServiceImpl → Repository (Spring Data
JPA) / Mapper (MapStruct) → Entity`.

## Endpoints implementados

| Recurso | Rotas |
|---|---|
| Auth | `POST /auth/register`, `/auth/login`, `/auth/oauth/{provider}`, `/auth/refresh`, `/auth/logout`, `/auth/logout-all` |
| Users | `GET /users`, `GET/PATCH/DELETE /users/me`, `GET /users/{userId}` |
| Followers | `GET /users/{userId}/followers`, `GET /users/{userId}/following`, `POST/DELETE /users/{userId}/follow`, `GET /users/me/follow-requests`, `POST /users/me/follow-requests/{requesterId}/accept`, `DELETE /users/me/follow-requests/{requesterId}` |
| Followed people (TMDB) | `POST/DELETE /users/me/follow-people/{personTmdbId}`, `GET /users/{userId}/follow-people` |
| Content | `POST /contents/reference` |
| Feed | `GET /feed` |

A API é protegida por autenticação por padrão: toda rota exige uma sessão válida, exceto `/auth/**`
(necessária para obter essa sessão) e `/error`.

## Segurança

- JWT stateless entregue via cookies `httpOnly` (`access_token`, `refresh_token`), nunca por header
  `Authorization`
- Refresh tokens rastreados no banco e rotacionados/revogados em `/auth/refresh` e `/auth/logout`
- CSRF habilitado para toda rota autenticada e mutável (cookie `XSRF-TOKEN`, header `X-XSRF-TOKEN`)
- Rate limiting por IP/conta em login, registro, OAuth, refresh, exclusão/alteração de conta, follow
  e criação de referência de conteúdo

## Roadmap

O modelo lógico completo, o contrato de API (OpenAPI) e a ordem de implementação por fases são
documentos internos do projeto (não versionados neste repositório).