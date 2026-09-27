# Task 1 — Centralizar o lock advisory e fixar a fronteira de tempo GMT

## Status da execução

Implementação concluída. O contrato e o bean PostgreSQL foram centralizados; o calendário foi migrado sem alterar suas identidades ou seu comportamento. A verificação PostgreSQL real ficou bloqueada porque o ambiente não possui Docker válido.

## Implementação

- Criado `AdvisoryLock` em `common.transaction` com `lock(String identity)`.
- Criado `PostgresAdvisoryLock` como bean `@Repository`, usando exatamente `SELECT pg_advisory_xact_lock(hashtext(?))` via `JdbcTemplate`.
- `CalendarScheduleSnapshotStore` agora recebe `AdvisoryLock`; a construção das identidades `movie|<tmdbId>|<region>|<language>` e `series|<tmdbId>|<region>|<language>` permaneceu inalterada.
- Removidos `CalendarScheduleIdentityLock` e `PostgresCalendarScheduleIdentityLock`.
- `CalendarScheduleCompletenessRepositoryTest` foi migrado para o contrato e bean comuns.
- Criado `PostgresAdvisoryLockTest` com duas transações reais, PIDs PostgreSQL distintos, consulta de `pg_locks`, serialização para identidade igual e ausência de serialização para identidades diferentes.

## Arquivos

Criados: `src/main/java/com/watchwise/watchwise_api/common/transaction/AdvisoryLock.java`, `src/main/java/com/watchwise/watchwise_api/common/transaction/PostgresAdvisoryLock.java`, `src/test/java/com/watchwise/watchwise_api/common/transaction/PostgresAdvisoryLockTest.java`.

Alterados: `src/main/java/com/watchwise/watchwise_api/calendar/repository/CalendarScheduleSnapshotStore.java`, `src/test/java/com/watchwise/watchwise_api/calendar/repository/CalendarScheduleCompletenessRepositoryTest.java`.

Removidos: `src/main/java/com/watchwise/watchwise_api/calendar/repository/CalendarScheduleIdentityLock.java`, `src/main/java/com/watchwise/watchwise_api/calendar/repository/PostgresCalendarScheduleIdentityLock.java`.

## RED/GREEN evidence

RED: `.\mvnw.cmd test "-Dtest=PostgresAdvisoryLockTest"` primeiro falhou antes da compilação porque o Maven tentou criar `C:\.m2\repository` e recebeu `LocalRepositoryNotAccessibleException`. Com um repositório local temporário, o teste então compilou até `testCompile` e falhou como esperado pela ausência de `AdvisoryLock` e `PostgresAdvisoryLock` (`cannot find symbol`).

GREEN disponível: após a implementação, `.\mvnw.cmd "-Dmaven.repo.local=<workspace>\.m2" test-compile -DskipTests` terminou com `BUILD SUCCESS`, compilando 453 fontes principais e 171 fontes de teste. `.\mvnw.cmd "-Dmaven.repo.local=<workspace>\.m2" test "-Dtest=CalendarScheduleSynchronizerImplTest"` terminou com `Tests run: 14, Failures: 0, Errors: 0, Skipped: 0` e `BUILD SUCCESS`.

Integração PostgreSQL: `.\mvnw.cmd "-Dmaven.repo.local=<workspace>\.m2" test "-Dtest=PostgresAdvisoryLockTest"` compilou o projeto e iniciou o teste, mas terminou com `Tests run: 1, Failures: 0, Errors: 1` e `java.lang.IllegalStateException: Could not find a valid Docker environment`. O log do Testcontainers informa que não encontrou configuração Docker válida, incluindo named pipe/estratégias disponíveis. Nenhum teste Testcontainers foi declarado como aprovado.

Uma compilação final com outro cache temporário foi interrompida pelo usuário enquanto ainda baixava dependências; o processo Java Maven remanescente foi encerrado antes do commit.

## Self-review

- O lock continua sendo PostgreSQL e transacional; não há `synchronized`, mapa local ou outro lock JVM.
- O SQL e as identidades do calendário não foram alterados.
- O teste de mesma identidade observa uma espera `granted = false` em `pg_locks` antes de liberar a primeira transação, evitando uma asserção baseada apenas em mock ou em ordem acidental.
- O teste de identidades diferentes exige aquisição da segunda transação enquanto a primeira ainda está retida.
- As duas transações comprovam conexões distintas comparando `pg_backend_pid()`.
- Não foram adicionados comentários de código e não há referências às classes removidas em `src/main` ou `src/test`.
- O `.gitignore` pré-existente não foi alterado.

## Concerns

- O teste de integração novo precisa ser executado em ambiente com Docker/Testcontainers disponível antes de considerar a prova PostgreSQL completa.
- A suíte Maven completa não foi executada por causa do bloqueio de Docker e do download prolongado de dependências; os checks focados disponíveis foram executados e registrados acima.
