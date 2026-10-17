# Home Upcoming Releases Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `subagent-driven-development` (recommended) or `executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expor no `HomeSummary` até seis próximos eventos de lançamento personalizados para o usuário autenticado, reutilizando a lógica do calendário.

**Architecture:** O serviço de calendário ganhará uma leitura de próximos eventos sobre o mesmo pipeline de interesses, snapshots, refresh, agrupamento e ordenação usado pelo calendário mensal. `SummaryServiceImpl` consumirá essa leitura com o `viewerId`, tratará indisponibilidade do TMDB como lista vazia e incluirá o resultado em `HomeSummaryResponseDTO`.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA, JUnit 5, Mockito, AssertJ, MockMvc, Testcontainers, Maven Wrapper.

## Global Constraints

- Manter o fluxo `Controller → Service → ServiceImpl → Repository/Mapper` e os pacotes por feature.
- Não criar entidade, tabela, referência `Content` ou endpoint novo.
- Usar `CalendarEventDTO` e os agrupamentos `MOVIE`, `EPISODE`, `SEASON` e `SERIES` já definidos pelo calendário.
- O limite da Home é seis eventos; o filtro de data é inclusivo a partir do dia atual do relógio de domínio.
- A nova leitura da Home usa `viewerId`, não o `userId` da rota, para escolher interesses, região, idioma e agenda personalizada.
- Não consultar o TMDB diretamente no serviço de Summary; reutilizar o pipeline de snapshots do calendário.
- Se a agenda ficar indisponível por `TmdbUnavailableException`, `summary/home` continua respondendo e serializa `upcomingReleases` como lista vazia.
- Atualizar `docs/context/openapi.yaml`, a documentação de telas, as regras de negócio quando aplicável e `docs/context/progress.md` junto da implementação; manter alterações exclusivamente documentais fora do commit, conforme `AGENTS.md`.
- Usar commits Conventional Commits, sem `Co-Authored-By`, e não executar push sem autorização explícita.

---

## File Map

**Calendar projection**

- Modify: `src/main/java/com/watchwise/watchwise_api/calendar/service/CalendarService.java` — declarar a leitura limitada de próximos eventos.
- Modify: `src/main/java/com/watchwise/watchwise_api/calendar/service/impl/CalendarServiceImpl.java` — compartilhar o pipeline de leitura/refresh e implementar a projeção futura.
- Modify: `src/main/java/com/watchwise/watchwise_api/calendar/service/impl/CalendarEventAssembler.java` — separar a montagem/ordenação comum do filtro mensal e adicionar filtro futuro com limite.
- Modify: `src/test/java/com/watchwise/watchwise_api/calendar/service/impl/CalendarEventAssemblerTest.java` — testar data inclusiva, exclusão de passado, limite, ordem e agrupamentos.
- Modify: `src/test/java/com/watchwise/watchwise_api/calendar/service/impl/CalendarServiceImplTest.java` — testar leitura futura, interesse vazio, refresh e propagação de indisponibilidade.

**Home response and service**

- Modify: `src/main/java/com/watchwise/watchwise_api/summary/dto/HomeSummaryResponseDTO.java` — adicionar `List<CalendarEventDTO> upcomingReleases` e preservar o construtor de compatibilidade.
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/service/impl/SummaryServiceImpl.java` — chamar o calendário com `viewerId`, tratar indisponibilidade e montar o novo campo.
- Modify: `src/test/java/com/watchwise/watchwise_api/summary/service/impl/SummaryServiceImplTest.java` — cobrir integração com o calendário, contexto do viewer e fallback vazio.
- Modify: `src/test/java/com/watchwise/watchwise_api/summary/controller/SummaryControllerIntegrationTest.java` — verificar o campo na resposta HTTP da Home.

**Contract and project documentation**

- Modify: `docs/context/openapi.yaml` — documentar `HomeSummary.upcomingReleases` com `maxItems: 6` e `CalendarEvent`.
- Modify: `docs/context/telas.md` — substituir a descrição antiga do preview da Home pela seção dos seis próximos eventos.
- Modify: `docs/context/telas-reqs.md` — sincronizar o requisito da Home no contexto técnico.
- Modify: `docs/telas-reqs-definitivo.md` — sincronizar o requisito consolidado da Home.
- Modify: `docs/context/business-rules.md` — acrescentar as regras de seleção por `viewerId`, data inclusiva e fallback resiliente da Home na seção `Calendar`/`Summary`.
- Modify: `docs/context/progress.md` — acrescentar o que foi efetivamente entregue no dia 2026-10-09, sem seção de próximos passos.

---

### Task 1: Extrair a projeção comum de eventos e criar a leitura futura do assembler

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/calendar/service/impl/CalendarEventAssembler.java`
- Test: `src/test/java/com/watchwise/watchwise_api/calendar/service/impl/CalendarEventAssemblerTest.java`

**Interfaces:**
- Consumes: `CalendarAssemblyInput`, snapshots validados, fontes, estados assistidos e completude já existentes.
- Produces: `List<CalendarEventDTO> assembleUpcoming(CalendarAssemblyInput input, LocalDate fromInclusive, int limit)`; o método existente `assemble(CalendarAssemblyInput)` continua retornando somente o mês solicitado.

- [ ] **Step 1: Escrever os testes que falham para a projeção futura**

Adicionar testes em `CalendarEventAssemblerTest` com um `CalendarAssemblyInput` cujo relógio esteja fixo em 2026-09-15:

```java
@Test
void shouldReturnAtMostSixEventsFromTodayInCalendarOrder() {
    List<CalendarEventDTO> result = assembler.assembleUpcoming(
            inputWithSnapshots(
                    movie("1", LocalDate.of(2026, 9, 15)),
                    movie("2", LocalDate.of(2026, 9, 16)),
                    movie("3", LocalDate.of(2026, 9, 17)),
                    movie("4", LocalDate.of(2026, 9, 18)),
                    movie("5", LocalDate.of(2026, 9, 19)),
                    movie("6", LocalDate.of(2026, 9, 20)),
                    movie("7", LocalDate.of(2026, 9, 21))),
            LocalDate.of(2026, 9, 15),
            6);

    assertThat(result).hasSize(6);
    assertThat(result).extracting(CalendarEventDTO::date)
            .containsExactly(
                    LocalDate.of(2026, 9, 15),
                    LocalDate.of(2026, 9, 16),
                    LocalDate.of(2026, 9, 17),
                    LocalDate.of(2026, 9, 18),
                    LocalDate.of(2026, 9, 19),
                    LocalDate.of(2026, 9, 20));
}
```

Adicionar antes dos testes o helper usado no exemplo:

```java
private CalendarAssemblyInput inputWithSnapshots(CalendarScheduleSnapshot... snapshots) {
    return new CalendarAssemblyInput(
            MONTH, CLOCK, List.of(snapshots), Map.of(), Set.of(), "BR", "pt-BR",
            completeness(Set.of(), Set.of()));
}
```

Esse helper usa os builders `movie(String, LocalDate)` já existentes na classe de teste.

Adicionar também testes para excluir datas anteriores, preservar um agrupamento `SEASON`/`SERIES` quando a completude permitir, retornar menos de seis quando necessário e retornar vazio para limite não positivo. Reutilizar os builders privados existentes, sem alterar os testes atuais do filtro mensal.

- [ ] **Step 2: Executar os testes para confirmar a falha**

Run: `mvnw.cmd test "-Dtest=CalendarEventAssemblerTest"`

Expected: FAIL porque `assembleUpcoming` ainda não existe.

- [ ] **Step 3: Implementar a montagem futura sem duplicar a lógica de eventos**

Refatorar `CalendarEventAssembler` para que a montagem dos candidatos fique em um método privado que:

```java
private List<AssembledEvent> assembleEvents(CalendarAssemblyInput input)
```

Esse método deve aplicar as validações de coordenadas/locale, adicionar filmes e episódios, preservar os agrupamentos atuais e ordenar com `EVENT_ORDER`. O método existente `assemble` deve filtrar esse resultado por `YearMonth.from(event.date())` e mapear para `CalendarEventDTO`.

Implementar:

```java
public List<CalendarEventDTO> assembleUpcoming(
        CalendarAssemblyInput input, LocalDate fromInclusive, int limit) {
    Objects.requireNonNull(fromInclusive, "fromInclusive is required");
    if (limit <= 0) {
        return List.of();
    }
    return assembleEvents(input).stream()
            .filter(event -> !event.date().isBefore(fromInclusive))
            .limit(limit)
            .map(AssembledEvent::event)
            .toList();
}
```

O método deve usar a ordem existente, sem introduzir ordenação por título ou por horário. O mês presente em `CalendarAssemblyInput` continua necessário para preservar a API do record; `assembleUpcoming` não deve usá-lo para filtrar.

- [ ] **Step 4: Executar os testes da unidade**

Run: `mvnw.cmd test "-Dtest=CalendarEventAssemblerTest"`

Expected: PASS, incluindo todos os testes antigos de agrupamento, status, locale e ordenação mensal.

- [ ] **Step 5: Fazer commit do primeiro incremento de código**

```powershell
git add src/main/java/com/watchwise/watchwise_api/calendar/service/impl/CalendarEventAssembler.java src/test/java/com/watchwise/watchwise_api/calendar/service/impl/CalendarEventAssemblerTest.java
git commit -m "feat(calendar): add upcoming event projection"
```

Depois do commit, confirmar explicitamente que a mensagem não contém `Co-Authored-By` nem outra forma de autoatribuição.

### Task 2: Expor próximos eventos pelo serviço de calendário sem duplicar refresh

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/calendar/service/CalendarService.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/calendar/service/impl/CalendarServiceImpl.java`
- Test: `src/test/java/com/watchwise/watchwise_api/calendar/service/impl/CalendarServiceImplTest.java`

**Interfaces:**
- Consumes: `CalendarEventAssembler.assembleUpcoming`, `CalendarInterestReader`, `CalendarScheduleSnapshotStore`, `CalendarScheduleProvider`, `CalendarScheduleSynchronizer` e `CalendarWatchedContentReader` existentes.
- Produces: `List<CalendarEventDTO> getUpcoming(UUID userId, int limit)`.

- [ ] **Step 1: Escrever testes que falham para o serviço futuro**

Adicionar em `CalendarServiceImplTest`:

```java
@Test
void shouldReturnUpcomingEventsUsingTheSharedInterestReadModel() {
    CalendarScheduleKey movieKey = key(ContentType.MOVIE, "550");
    CalendarInterest interest = interest(Map.of(movieKey, Set.of(CalendarSource.WATCHLIST)));
    CalendarScheduleSnapshot today = movie("550", LocalDate.of(2026, 9, 12));
    CalendarScheduleSnapshot future = movie("551", LocalDate.of(2026, 9, 13));
    when(interestReader.read(USER_ID)).thenReturn(interest);
    when(snapshotStore.findForInterest(interest, "BR", "pt-BR"))
            .thenReturn(new CalendarScheduleReadModel(
                    List.of(today, future),
                    CalendarAssemblyInput.Completeness.empty()));
    when(watchedReader.readWatchedKeys(eq(USER_ID), any())).thenReturn(Set.of());

    List<CalendarEventDTO> result = service().getUpcoming(USER_ID, 6);

    assertThat(result).extracting(CalendarEventDTO::date)
            .containsExactly(LocalDate.of(2026, 9, 12), LocalDate.of(2026, 9, 13));
}
```

Adicionar testes para interesse vazio sem acesso a snapshots, exclusão de um snapshot anterior ao relógio fixo, limitação de seis e propagação de `TmdbUnavailableException` quando a própria leitura do calendário não tem dados utilizáveis. O teste deve usar o relógio fixo já declarado em `CalendarServiceImplTest`.

- [ ] **Step 2: Executar os testes para confirmar a falha**

Run: `mvnw.cmd test "-Dtest=CalendarServiceImplTest"`

Expected: FAIL porque a interface e a implementação ainda não possuem `getUpcoming`.

- [ ] **Step 3: Adicionar a operação à interface**

Em `CalendarService.java`, declarar:

```java
List<CalendarEventDTO> getUpcoming(UUID userId, int limit);
```

Importar `CalendarEventDTO` e `List` sem alterar `getMonth`.

- [ ] **Step 4: Extrair o resultado compartilhado da leitura do calendário**

Em `CalendarServiceImpl`, mover o bloco que hoje começa em `interestReader.read(userId)` e termina antes da montagem específica de `CalendarResponseDTO` para um método privado:

```java
private CalendarReadResult readCalendar(UUID userId)
```

Criar um record privado com estes campos:

```java
private record CalendarReadResult(
        CalendarInterest interest,
        List<CalendarScheduleSnapshot> snapshots,
        Map<CalendarScheduleKey, Set<CalendarSource>> sourcesByKey,
        Set<WatchedCalendarKey> watchedKeys,
        CalendarAssemblyInput.Completeness completeness) {
}
```

O método deve conservar a sequência atual: ler interesses, retornar um resultado vazio para nenhum interesse, consultar snapshots, atualizar somente o que estiver ausente ou vencido, recarregar o read model quando houve sincronização remota, remover chaves omitidas, resolver estados assistidos e filtrar completude incerta. Nenhuma chamada adicional ao TMDB deve ser criada.

Para o resultado, `sourcesByKey` deve excluir `omittedKeys`, `watchedKeys` deve ser calculado dos snapshots finais e `completeness` deve ser o retorno de `filterUncertainCompleteness`.

- [ ] **Step 5: Implementar `getMonth` sobre o resultado compartilhado**

Manter o contrato atual de `CalendarResponseDTO` e construir `CalendarAssemblyInput` usando o `YearMonth` solicitado, os campos do `CalendarReadResult`, o `region`, o idioma e o `clock`. Passar o input ao `eventAssembler.assemble(input)` para preservar o comportamento mensal existente.

- [ ] **Step 6: Implementar `getUpcoming` sobre o mesmo resultado**

Adicionar em `CalendarServiceImpl`:

```java
@Override
public List<CalendarEventDTO> getUpcoming(UUID userId, int limit) {
    if (limit <= 0) {
        return List.of();
    }
    CalendarReadResult read = readCalendar(userId);
    LocalDate today = LocalDate.now(clock.withZone(ZoneId.systemDefault()));
    CalendarAssemblyInput input = new CalendarAssemblyInput(
            YearMonth.from(today), clock, read.snapshots(), read.sourcesByKey(),
            read.watchedKeys(), read.interest().preferredRegion(),
            read.interest().preferredLanguage(), read.completeness());
    return eventAssembler.assembleUpcoming(input, today, limit);
}
```

Para interesse vazio, `readCalendar` deve permitir que o método retorne `List.of()` sem consultar snapshots, provider, synchronizer ou watched reader.

- [ ] **Step 7: Executar os testes de calendário**

Run: `mvnw.cmd test "-Dtest=CalendarEventAssemblerTest,CalendarServiceImplTest"`

Expected: PASS, sem regressões nos testes existentes de calendário mensal, refresh, snapshots, completude, locale e indisponibilidade.

- [ ] **Step 8: Fazer commit do incremento do serviço de calendário**

```powershell
git add src/main/java/com/watchwise/watchwise_api/calendar/service/CalendarService.java src/main/java/com/watchwise/watchwise_api/calendar/service/impl/CalendarServiceImpl.java src/test/java/com/watchwise/watchwise_api/calendar/service/impl/CalendarServiceImplTest.java
git commit -m "feat(calendar): expose upcoming events"
```

Depois do commit, confirmar explicitamente a ausência de `Co-Authored-By` e de autoatribuição.

### Task 3: Integrar os eventos na resposta da Home

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/dto/HomeSummaryResponseDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/service/impl/SummaryServiceImpl.java`
- Test: `src/test/java/com/watchwise/watchwise_api/summary/service/impl/SummaryServiceImplTest.java`

**Interfaces:**
- Consumes: `CalendarService.getUpcoming(UUID, int)`.
- Produces: `HomeSummaryResponseDTO.upcomingReleases()` sempre como lista não nula, limitada pela constante `HOME_UPCOMING_RELEASES_LIMIT = 6`.

- [ ] **Step 1: Escrever testes que falham para o contrato e o serviço**

Em `SummaryServiceImplTest`, adicionar um `@Mock CalendarService calendarService` e configurar o caso padrão no `setUp`:

```java
lenient().when(calendarService.getUpcoming(any(), eq(6))).thenReturn(List.of());
```

Adicionar um teste que passe ids diferentes e verifique o contexto do viewer:

```java
@Test
void shouldLoadUpcomingReleasesForTheViewerNotTheTargetProfile() {
    when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
    CalendarEventDTO event = new CalendarEventDTO(
            LocalDate.of(2026, 9, 20), CalendarEventType.MOVIE,
            ReleaseStatus.UPCOMING, WatchStatus.UNWATCHED,
            Set.of(CalendarSource.WATCHLIST),
            new MovieCalendarContentDTO("550", "Movie 550", "/movie-550.jpg"));
    when(calendarService.getUpcoming(marinaId, 6)).thenReturn(List.of(event));

    HomeSummaryResponseDTO result = summaryService.getHomeSummary(marinaId, lucasId);

    assertThat(result.upcomingReleases()).containsExactly(event);
    verify(calendarService).getUpcoming(marinaId, 6);
}
```

Adicionar também o teste que faz `calendarService.getUpcoming(...)` lançar `TmdbUnavailableException` e verifica `result.upcomingReleases().isEmpty()`, além de ajustar o teste de totais para confirmar que o array vazio é preservado.

Atualizar os construtores de teste que chamam `HomeSummaryResponseDTO` diretamente apenas quando necessário; o construtor de compatibilidade deve continuar funcionando e preencher `upcomingReleases` com `List.of()`.

Adicionar os imports de `CalendarEventType`, `CalendarSource`, `MovieCalendarContentDTO`, `ReleaseStatus` e `WatchStatus` usados na construção explícita do evento de teste.

- [ ] **Step 2: Executar os testes para confirmar a falha**

Run: `mvnw.cmd test "-Dtest=SummaryServiceImplTest"`

Expected: FAIL na compilação porque `HomeSummaryResponseDTO` ainda não expõe o campo e `SummaryServiceImpl` ainda não injeta `CalendarService`.

- [ ] **Step 3: Adicionar o campo ao DTO sem quebrar o construtor legado**

Importar `CalendarEventDTO` e adicionar o campo `List<CalendarEventDTO> upcomingReleases` ao record principal. Posicioná-lo imediatamente depois de `nextEpisodes` para manter os blocos relacionados da Home juntos.

Atualizar o construtor principal usado por `SummaryServiceImpl` e alterar o construtor de compatibilidade para delegar com `List.of()` como `upcomingReleases`. Não aceitar `null` produzido pelo serviço; a lista vazia deve ser a representação padrão.

- [ ] **Step 4: Integrar o serviço de calendário com fallback isolado**

Em `SummaryServiceImpl`:

```java
private static final int HOME_UPCOMING_RELEASES_LIMIT = 6;
private final CalendarService calendarService;
```

Dentro de `getHomeSummary`, depois da validação de visibilidade e antes da construção do DTO, carregar:

```java
List<CalendarEventDTO> upcomingReleases;
try {
    upcomingReleases = calendarService.getUpcoming(viewerId, HOME_UPCOMING_RELEASES_LIMIT);
} catch (TmdbUnavailableException exception) {
    upcomingReleases = List.of();
}
```

Passar `upcomingReleases` ao construtor principal. Não capturar `Exception`, não esconder erros de programação e não trocar `viewerId` por `userId`.

- [ ] **Step 5: Executar os testes unitários da Home**

Run: `mvnw.cmd test "-Dtest=SummaryServiceImplTest"`

Expected: PASS, incluindo privacidade, usuário inexistente, compatibilidade do construtor e fallback do TMDB.

- [ ] **Step 6: Fazer commit da integração de código da Home**

```powershell
git add src/main/java/com/watchwise/watchwise_api/summary/dto/HomeSummaryResponseDTO.java src/main/java/com/watchwise/watchwise_api/summary/service/impl/SummaryServiceImpl.java src/test/java/com/watchwise/watchwise_api/summary/service/impl/SummaryServiceImplTest.java
git commit -m "feat(home): add upcoming releases"
```

Depois do commit, confirmar explicitamente a ausência de `Co-Authored-By` e de autoatribuição.

### Task 4: Verificar o contrato HTTP e sincronizar a documentação

**Files:**
- Modify: `src/test/java/com/watchwise/watchwise_api/summary/controller/SummaryControllerIntegrationTest.java`
- Modify: `docs/context/openapi.yaml`
- Modify: `docs/context/telas.md`
- Modify: `docs/context/telas-reqs.md`
- Modify: `docs/telas-reqs-definitivo.md`
- Modify: `docs/context/business-rules.md`
- Modify: `docs/context/progress.md`

**Interfaces:**
- Consumes: o `HomeSummary` serializado pelo endpoint existente.
- Produces: contrato documentado com `upcomingReleases` e evidência de resposta HTTP com array vazio ou seis eventos.

- [ ] **Step 1: Escrever o teste de integração HTTP**

No `SummaryControllerIntegrationTest`, registrar um `@MockitoBean CalendarService calendarService` para manter o teste da Home determinístico. Configurar `when(calendarService.getUpcoming(any(), eq(6))).thenReturn(List.of())` no `setUp` e adicionar ao teste de sucesso:

```java
.andExpect(jsonPath("$.upcomingReleases").isArray())
.andExpect(jsonPath("$.upcomingReleases").isEmpty());
```

Adicionar um teste com um `CalendarEventDTO` completo para verificar que o endpoint serializa `eventType`, `date`, `releaseStatus`, `watchStatus`, `sources` e `content` sem alteração do formato `CalendarEvent`.

- [ ] **Step 2: Executar o teste de integração**

Run: `mvnw.cmd test "-Dtest=SummaryControllerIntegrationTest"`

Expected: PASS com o container PostgreSQL disponível. Se Docker não estiver disponível, registrar a limitação sem substituir o teste por uma afirmação de sucesso.

- [ ] **Step 3: Atualizar o contrato OpenAPI**

Adicionar `upcomingReleases` em `components.schemas.HomeSummary` com `maxItems: 6`, descrição da personalização pelo viewer e itens apontando para `CalendarEvent`. Atualizar a descrição de `/users/{userId}/summary/home` para indicar que o bloco vem na mesma resposta e não exige que a interface consulte o calendário mensal.

- [ ] **Step 4: Atualizar as telas e regras de negócio**

Em `docs/context/telas.md`, `docs/context/telas-reqs.md` e `docs/telas-reqs-definitivo.md`, substituir o requisito antigo de preview limitado por dias por uma seção de até seis eventos futuros, preservando a origem personalizada do calendário e os quatro tipos de agrupamento.

Em `docs/context/business-rules.md`, na seção `Calendar` ou `Summary` que já concentra as regras de leitura, registrar somente as regras não óbvias efetivamente codificadas: seleção pelo `viewerId`, data inclusiva e fallback da Home para lista vazia em indisponibilidade do TMDB. Não adicionar uma entrada para o limite genérico de seis, que é um detalhe de contrato da Home.

- [ ] **Step 5: Atualizar o progresso cronológico**

Adicionar uma entrada em `docs/context/progress.md` sob o novo título `## 2026-10-09 — Próximos lançamentos personalizados na Home`, sem emoji e sem “próximos passos”, registrando apenas a implementação concluída e o comportamento entregue.

- [ ] **Step 6: Rodar a verificação documental**

Run: `git diff --check -- docs/context/openapi.yaml docs/context/telas.md docs/context/telas-reqs.md docs/telas-reqs-definitivo.md docs/context/business-rules.md docs/context/progress.md`

Expected: nenhuma falha de whitespace. Confirmar com `rg` que `upcomingReleases`, `maxItems: 6` e a descrição do viewer aparecem no OpenAPI e que o texto antigo de “Preview de 5 dias” não continua apresentando o requisito como único comportamento da Home.

Não adicionar esses arquivos ao commit de código, conforme a regra de documentação do repositório.

### Task 5: Verificação final e fechamento

**Files:**
- Verify: todos os arquivos de código e teste alterados nas Tasks 1–4.
- Verify: `docs/context/openapi.yaml`, `docs/context/telas.md`, `docs/context/telas-reqs.md`, `docs/telas-reqs-definitivo.md`, `docs/context/business-rules.md`, `docs/context/progress.md`.

**Interfaces:**
- Consumes: código integrado, testes unitários, teste HTTP e documentação sincronizada.
- Produces: evidência de build/testes e estado do worktree pronto para revisão; nenhum push.

- [ ] **Step 1: Executar os testes direcionados finais**

Run: `mvnw.cmd test "-Dtest=CalendarEventAssemblerTest,CalendarServiceImplTest,SummaryServiceImplTest,SummaryControllerIntegrationTest"`

Expected: PASS em todas as classes selecionadas.

- [ ] **Step 2: Executar a suíte completa**

Run: `mvnw.cmd test`

Expected: PASS. Repository tests exigem Docker em execução.

- [ ] **Step 3: Revisar o diff e procurar lacunas recorrentes**

Run: `git diff --check; git status --short; rg -n -S "upcomingReleases|getUpcoming|HOME_UPCOMING_RELEASES_LIMIT" src/main src/test docs/context/openapi.yaml docs/context/telas.md docs/context/telas-reqs.md docs/telas-reqs-definitivo.md docs/context/business-rules.md docs/context/progress.md`

Confirmar manualmente:

- nenhum caminho chama TMDB diretamente a partir de `SummaryServiceImpl`;
- o limite de seis existe no serviço da Home e também no contrato;
- `viewerId` é usado para a leitura personalizada;
- o `CalendarService` mensal não perdeu seu comportamento de erro;
- a lista nunca é `null`;
- exceções inesperadas continuam passando pelo `GlobalExceptionHandler` em vez de serem capturadas pelo fallback;
- documentação está sincronizada, mas não foi incluída nos commits de código.

- [ ] **Step 4: Entregar o resultado**

Informar os commits locais, os testes executados, a documentação deixada no worktree e que nenhum push foi feito. Se o usuário quiser publicar os commits, pedir autorização explícita antes de executar qualquer push.
