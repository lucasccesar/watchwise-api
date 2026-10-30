# Preparação Heroku, R2 e memória limitada — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Execução sequencial; não delegar tarefas sem autorização aplicável.

**Goal:** Preparar armazenamento de imports, configuração de produção, conexões e caches do Watchwise para Heroku Basic, Heroku Postgres Essential-0 e R2 privado, com verificação local antes do provisionamento externo.

**Architecture:** Preservar `ImportFileStore` e selecionar local/R2 por configuração. Materializar arquivos em sessões temporárias privadas e verificadas antes do parsing. Reutilizar o filtro multipart, a fila de imports e a limpeza existentes; repartir um orçamento de cache entre os vinte caches TMDB e o cache de imagens.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Maven Wrapper, PostgreSQL/Flyway, AWS SDK for Java v2 2.57.0, Caffeine, JUnit 5, AssertJ, Mockito, servidor HTTP local do JDK e Testcontainers PostgreSQL.

**Spec:** [2026-10-28-heroku-r2-production-preparation-design.md](../specs/2026-10-28-heroku-r2-production-preparation-design.md).

**Base inspecionada:** `main`, `ce86410d`. A implementação começou em `main` após o commit de manutenção de fixtures `919372a9`. **Estado deste arquivo:** tarefas 1–12 concluídas nos commits `92f5911d`, `7136de69`, `6ffa7e45`, `7c35318a`, `f65161f4`, `beb85335`, `b6ebd212`, `468d6111`, `37dc6092`, `3e1a36b9` e `262f3914`. A bateria de integração mantém dois erros conhecidos fora do escopo nesta rodada, documentados na tarefa 12.2. A validação integral da tarefa 6 mantém dois erros preexistentes em exclusão de listas, descritos na tarefa 6.5.

## Global Constraints

- Uma instância Heroku Basic executando API, worker de importação e agendamentos existentes no mesmo processo.
- Buildpack Java oficial do Heroku, Maven e Java 21.
- Heroku Postgres Essential-0; bucket privado R2 Standard.
- Upload HTTP continua passando pela API e usando as rotas atuais.
- Worker em prod: intervalo inicial de 5.000 ms após o término da execução; geração dos jogos preservada a cada dez minutos.
- Hikari em prod: máximo inicial de oito conexões, mínimo ocioso de duas e espera de 5.000 ms por conexão.
- Limites atuais: arquivo original 50 MiB; Picks-template 5 MiB; expansão 512 MiB; entrada expandida 64 MiB; requisição multipart 51 MiB.
- Dois uploads HTTP e duas materializações simultâneas por processo, configuráveis. Não criar filas ilimitadas de threads esperando slots.
- Não usar URLs públicas, upload direto do navegador, Redis, outro dyno ou outra fila.
- Não mudar rotas, DTOs públicos, cookies/CSRF, domínios ou prefixo `/api/v1`.
- Não criar schema novo para selecionar storage; reutilizar tamanho/digest e tarefas de limpeza persistidos.
- Não prometer memória exata, compatibilidade R2 real ou capacidade para cem usuários a partir de testes locais.
- Não preencher o orçamento final de cache com um valor apresentado como medido. Prod weighted requer valor explícito; modo disabled permite medir o consumo base.
- Código, campos e tipos Java em inglês. Mensagens de commit em Conventional Commits, uma linha, sem justificativa ou atribuição.
- Este plano, a spec e demais arquivos de `docs/` permanecem locais na `main`, ignorados pelo Git. Não fazer commit de documentação.
- Na implementação futura, atualizar `progress.md` somente com o que realmente foi concluído e `business-rules.md` com regras efetivamente alteradas.
- Fazer commits locais de entregas concluídas; conferir e comunicar ausência de `Co-Authored-By` ou autoatribuição após cada commit. Push exige autorização específica.

## Como executar e conferir este plano

Todos os caminhos abaixo são relativos à raiz `D:/Users/Lucas C/Documentos/codigos/watchwise-api`. Os blocos Java mostram contratos e casos de teste a implementar, não código já existente ou validado. Usar os imports correspondentes ao pacote de cada arquivo e os padrões JUnit/AssertJ/Mockito já usados no repositório.

Organização em três entregas: armazenamento/temporários/imports (tarefas 1–7), produção/pool/agendamentos (tarefa 8), memória dos caches (tarefas 9–11). A tarefa 12 fecha a integração e o relatório. Cada tarefa deve compilar e passar seus testes antes de começar a seguinte.

Para código novo, escrever primeiro o caso que demonstra a regra e executar o teste antes da implementação. Uma falha de compilação por tipo inexistente pode ser o primeiro vermelho; depois de declarar a API mínima, confirmar também a falha de comportamento. Não aceitar um teste que verifica apenas a própria configuração de um mock.

Os comandos de testes abaixo são futuros, executados durante a implementação. A criação deste plano não executa testes nem comprova que os snippets compilam.

Após o verde de cada tarefa, revisar `git diff`, adicionar explicitamente os arquivos de código e testes listados nela e fazer o commit indicado. Não usar `git add .`, `git add -A` ou adicionar diretórios de documentação. Rodar `git log -1 --format=%B` e comunicar a ausência de atribuição. Se a árvore tiver mudanças de terceiros, não incluí-las no commit.

### Preparação antes da tarefa 1

- [x] Ler `AGENTS.md` e a spec completa; comparar o HEAD com a base acima e ajustar referências que tenham mudado.
- [x] Conferir `git status --short --branch` e `git worktree list`. O planejamento foi feito na `main`; preservar a preferência do usuário por documentação local nessa cópia. Não criar worktree só para documentos.
- [x] Conferir `./mvnw.cmd -version`. O executor deve usar Java 21 e o wrapper do projeto.
- [ ] Conferir Docker antes dos testes de PostgreSQL; testes de filesystem, HTTP local e caches não exigem R2, Heroku nem Docker.
- [ ] Separar falhas anteriores de regressões usando testes focados nas áreas a alterar, sem executar repetidamente toda a suíte como preâmbulo.

## Refinamentos do plano sobre os nomes propostos na spec

1. `ImportMultipartRequestGateFilter` já existe, tem testes e é instalado depois de `AuthorizationFilter`. Estendê-lo; não criar `ImportUploadAdmissionFilter` em paralelo.
2. A sessão temporária será aberta pelos handlers para cada `inspect` ou `normalize`. `ImportModeRegistry` conserva sua API; o contexto de leitura passa a transportar os metadados do job.
3. O buildpack já fornece `SPRING_DATASOURCE_*`. Não implementar conversor de `DATABASE_URL` nem um segundo datasource.
4. AWS SDK 2.57.0 é uma versão concreta verificada em release oficial de 2026-10-19. Usar BOM e módulos mínimos; não depender de `LATEST`.
5. Esses refinamentos concretizam a spec sem mudar as rotas ou o resultado solicitado.

## Tarefa 1: Ciclo de vida e orçamento de temporários privados

**Depende de:** preparação.

**Files:**
- Create: `src/main/java/com/watchwise/watchwise_api/platformimport/storage/ImportTemporaryFiles.java`
- Create: `src/main/java/com/watchwise/watchwise_api/platformimport/storage/TemporaryImportFile.java`
- Create: `src/main/java/com/watchwise/watchwise_api/platformimport/storage/MultipartUploadReservation.java`
- Create: `src/main/java/com/watchwise/watchwise_api/platformimport/config/ImportTemporaryConfiguration.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/platformimport/service/ImportLimits.java`
- Create: `src/test/java/com/watchwise/watchwise_api/platformimport/storage/ImportTemporaryFilesTest.java`
- Create: `src/test/java/com/watchwise/watchwise_api/platformimport/config/ImportTemporaryConfigurationTest.java`

**Interfaces:**
- Consome: raiz `Path`, limite de arquivo, limite de requisição e concorrências U/R.
- Produz os contratos abaixo; métodos de aquisição falham imediatamente quando não houver capacidade.

```java
public final class ImportTemporaryFiles implements AutoCloseable {
    public ImportTemporaryFiles(Path root, long maxUploadBytes, long maxRequestBytes,
                                int uploadSlots, int readSlots);
    public Path multipartDirectory();
    public MultipartUploadReservation admitMultipart();
    public TemporaryImportFile createUpload(long limit);
    public TemporaryImportFile createRead(long limit);
    public long reservedBytes();
    public int reapAbandoned();
    public void close();
}
public interface TemporaryImportFile extends AutoCloseable {
    Path path();
    void close();
}
public interface MultipartUploadReservation extends AutoCloseable {
    void close();
}
```

- [x] **1.1 — Escrever o teste de retenção e liberação real de espaço.** O teste abaixo pertence à classe com `@TempDir Path root`. Acrescentar testes de limite de slots, overflow, fechamento repetido e tentativa de fechar uma execução ainda ativa.

```java
@Test
void removesTemporaryFileAndReleasesItsReservation() throws Exception {
    try (var files = new ImportTemporaryFiles(root, 1024, 2048, 2, 2)) {
        Path path;
        try (var file = files.createUpload(1024)) {
            path = file.path();
            Files.write(path, new byte[] {1, 2, 3});
            assertThat(files.reservedBytes()).isEqualTo(1024);
        }
        assertThat(path).doesNotExist();
        assertThat(files.reservedBytes()).isZero();
    }
}
```

- [x] **1.2 — Executar vermelho:** `./mvnw.cmd test "-Dtest=ImportTemporaryFilesTest"`. Confirmar que o comportamento de reservar/remover ainda não existe. A classe ausente causou falha inicial de compilação; após declarar a API mínima, os testes falharam nas operações sem implementação.
- [x] **1.3 — Implementar diretórios e handles.** Criar diretório de execução com nome UUID e lock de processo, contendo `multipart`, `uploads` e `artifacts`. Validar raiz dedicada, normalização, ancestrais reais e symlinks antes da criação. Criar diretórios `0700` e arquivos `0600` quando POSIX existir. Os handles guardam suas reservas e usam fechamento idempotente.
- [x] **1.4 — Implementar reserva e recuperação.** Calcular com `Math.multiplyExact`/`Math.addExact` o teto `U*(M+F)+R*F`; validar F/M positivos, M >= F e concorrências positivas. Reservar bytes antes da escrita, verificar espaço livre e não manter fila de espera. Separar slots multipart/upload/leitura. Limitar arquivos registrados pendentes de limpeza a `U+R` e tentar remover órfãos antes de aceitar novas cópias, para que muitos arquivos pequenos não cresçam o mapa sem limite. A limpeza só libera reserva após confirmar exclusão. Marcadores de limpeza recuperam diretórios sem corrida entre processos e funcionam no Windows, onde não se apaga diretório enquanto o lock file está aberto.

```java
private static long maximumReservedBytes(long fileBytes, long requestBytes, int uploads, int reads) {
    return Math.addExact(
            Math.multiplyExact((long) uploads, Math.addExact(requestBytes, fileBytes)),
            Math.multiplyExact((long) reads, fileBytes));
}
```

Adicionar `maxConcurrentArtifactReadsPerProcess=2` e respectivos acessores ao `ImportLimits`. Não alterar os limites de expansão/registro existentes ao calcular esse orçamento de disco.
- [x] **1.5 — Provar limpeza difícil.** Testar symlink e raiz inadequada, execução com lock ativo, marcador deixado por processo encerrado e falha real de exclusão simulada com um diretório temporário não vazio. O teste POSIX/symlink é ignorado quando o filesystem do Windows não os oferece.
- [x] **1.6 — Verde e commit.** `./mvnw.cmd test "-Dtest=ImportTemporaryFilesTest,ImportTemporaryConfigurationTest,TmdbClientDisplayDetailsTest,ContentCardAssemblerTest"`: 30 testes passaram sem falhas ou erros; dois testes POSIX/symlink foram ignorados no Windows. Commit `92f5911d`: `feat(import): add private temporary file lifecycle`.

**Saída verificável:** arquivos reais são privados, removidos no fechamento, limitados por slots/bytes e recuperáveis depois de abandono, sem apagar execução ativa.

## Tarefa 2: Reutilizar o gate multipart e desacoplar o staging do storage local

**Depende de:** tarefa 1.

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/platformimport/config/ImportMultipartRequestGateFilter.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/platformimport/config/ImportMultipartStagingDirectory.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/common/config/SecurityConfig.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/platformimport/config/ImportTemporaryConfiguration.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/platformimport/storage/ImportTemporaryFiles.java`
- Modify: `src/main/resources/application-dev.properties`
- Modify: `src/main/resources/application-prod.properties`
- Create: `src/main/java/com/watchwise/watchwise_api/platformimport/config/ImportMultipartConfiguration.java`
- Modify: `src/test/java/com/watchwise/watchwise_api/platformimport/config/ImportMultipartRequestGateFilterTest.java`
- Modify: `src/test/java/com/watchwise/watchwise_api/platformimport/config/ImportMultipartStagingDirectoryTest.java`
- Modify: `src/test/java/com/watchwise/watchwise_api/platformimport/config/ImportTemporaryConfigurationTest.java`
- Create: `src/test/java/com/watchwise/watchwise_api/platformimport/config/ImportMultipartLifecycleIntegrationTest.java`
- Create: `src/test/java/com/watchwise/watchwise_api/platformimport/config/ImportMultipartConfigurationTest.java`

**Interfaces:**
- Consome: `ImportTemporaryFiles.admitMultipart()` e `.multipartDirectory()`.
- Produz: o gate com construtor `(ImportTemporaryFiles, ObjectMapper, Clock)` e um único `MultipartConfigElement` usando o diretório privado de execução.

- [x] **2.1 — Acrescentar vermelho ao teste do gate.** Preservar o teste existente de 429 antes de alcançar o resolver e acrescentar o caso de reserva liberada após exceção.

```java
@Test
void releasesMultipartReservationWhenTheChainFails() throws Exception {
    try (var files = new ImportTemporaryFiles(root, 1024, 2048, 1, 1)) {
        var gate = new ImportMultipartRequestGateFilter(files, new ObjectMapper(), Clock.systemUTC());
        var request = new MockHttpServletRequest("POST", "/api/v1/imports");
        request.setContextPath("/api/v1");
        request.setContentType("multipart/form-data; boundary=test");
        assertThatThrownBy(() -> gate.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> { throw new ServletException("resolver failed"); }))
                .isInstanceOf(ServletException.class);
        assertThat(files.reservedBytes()).isZero();
    }
}
```

- [x] **2.2 — Executar vermelho:** `./mvnw.cmd test "-Dtest=ImportMultipartRequestGateFilterTest,ImportMultipartStagingDirectoryTest"`. O código antigo não aceitava o manager e falhou na compilação dos testes, conforme esperado.
- [x] **2.3 — Adaptar o gate existente.** Substituir seu semáforo privado pela admissão do manager. Preservar `POST /imports`, remoção do context path, reconhecimento do content-type multipart e ordem depois de autorização/CSRF. O gate retorna o `ApiError` 429 existente; `FilterRegistrationBean.setEnabled(false)` evita registro fora da cadeia de segurança.
- [x] **2.4 — Configurar o servlet multipart e selecionar a raiz privada.** `staging-directory` e `temporary-directory` selecionam a mesma raiz privada; se ambos forem definidos, devem normalizar para o mesmo caminho. O manager cria `run-UUID/multipart`, `uploads` e `artifacts` sob essa raiz. Remover `@DependsOn("privateImportFileStore")` e a exigência de storage local quando R2 estiver selecionado. Registrar `ImportMultipartStagingDirectory` por factory no `ImportMultipartConfiguration`, sem bean duplicado. O bean `MultipartConfigElement` aponta para a pasta gerada pelo manager; retirar `spring.servlet.multipart.location` dos profiles. No backend local, validar que storage durável fica fora da raiz temporária e sem symlinks.
- [x] **2.5 — Fechar a contabilização multipart.** O filtro reserva bytes antes do resolver, mantém as reservas de requisições simultâneas até a limpeza pelo `DispatcherServlet`, remove apenas resíduos da pasta multipart exclusiva e libera slots uma vez em sucesso ou erro. Resíduos que não podem ser removidos mantêm a reserva até a varredura posterior.
- [x] **2.6 — Testar a fronteira real do servlet e commitar.** Os testes focados passaram 12 casos, sem falhas ou erros. `SecurityConfigIntegrationTest` passou mais sete casos com PostgreSQL Testcontainers. `ImportMultipartLifecycleIntegrationTest` usou Tomcat real e NIO2 no harness Windows porque o NIO não inicializa seu pipe de loopback nessa JVM. Commit `7136de69`: `refactor(import): decouple multipart staging from durable storage`.

**Saída verificável:** o backend R2 pode iniciar sem diretório durável local; o gate controla disco antes do parsing, preservando a cadeia de segurança.

## Tarefa 3: Adaptador R2, cliente mínimo e falhas de armazenamento

**Depende de:** tarefas 1–2.

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/java/com/watchwise/watchwise_api/platformimport/storage/PrivateImportFileStore.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/platformimport/storage/ImportFileStore.java`
- Create: `src/main/java/com/watchwise/watchwise_api/platformimport/storage/R2ImportFileStore.java`
- Create: `src/main/java/com/watchwise/watchwise_api/platformimport/storage/ImportStorageKeys.java`
- Create: `src/main/java/com/watchwise/watchwise_api/platformimport/storage/ImportStorageUnavailableException.java`
- Create: `src/main/java/com/watchwise/watchwise_api/platformimport/storage/ImportArtifactIntegrityException.java`
- Create: `src/main/java/com/watchwise/watchwise_api/platformimport/storage/ImportStorageKeyCollisionException.java`
- Create: `src/main/java/com/watchwise/watchwise_api/platformimport/storage/DeadlineInputStream.java`
- Create: `src/main/java/com/watchwise/watchwise_api/platformimport/config/ImportStorageConfiguration.java`
- Create: `src/main/java/com/watchwise/watchwise_api/platformimport/config/R2StorageProperties.java`
- Create: `src/test/java/com/watchwise/watchwise_api/platformimport/storage/S3StubServer.java`
- Create: `src/test/java/com/watchwise/watchwise_api/platformimport/storage/R2StoreFixture.java`
- Create: `src/test/java/com/watchwise/watchwise_api/platformimport/storage/R2ImportFileStoreTest.java`
- Create: `src/test/java/com/watchwise/watchwise_api/platformimport/config/ImportStorageConfigurationTest.java`
- Modify: `src/test/java/com/watchwise/watchwise_api/platformimport/storage/PrivateImportFileStoreTest.java`

**Interfaces:**
- Mantém integralmente `ImportFileStore`; adiciona `ImportStorageKeys.validate(String)` e `.objectKey(String)` retornando `imports/{uuid}`.
- `ImportStorageUnavailableException(Throwable)` estende `ServiceUnavailableException` com mensagem pública constante e causa interna.
- `ImportArtifactIntegrityException(Reason, Throwable)` usa enum `MISSING`/`CORRUPT`; `ImportStorageKeyCollisionException` representa somente colisão comprovada.
- `R2ImportFileStore(S3Client, R2StorageProperties, ImportTemporaryFiles, ImportLimits, ScheduledExecutorService)` recebe recursos explicitamente, sem buscar credenciais por conta própria.
- `R2StoreFixture.start(Path)` é utilitário apenas de testes, `AutoCloseable`, e expõe `store()`, `server()` e `temporary()`. Constrói cliente real contra `S3StubServer` em localhost, com credenciais fictícias, e fecha cliente, scheduler e manager.
- `S3StubServer` usa `ServerSocket` do JDK porque o listener padrão do Windows não estabeleceu loopback de forma confiável neste runtime. Implementa PUT/GET/HEAD/DELETE e erros S3 XML, registra requests, corpo e metadados. API de teste: `storedBody(String path)`, `requestCount(String method, String path)`, `lastHeaders(String method, String path)`, `failNext(String method, String path, int status, int times)`, `disconnectAfterNextPut()`, `delayNextGetBody(Duration)` e `truncateNextGetBody(int)`.

- [x] **3.1 — Escrever contrato real de round-trip.** O teste usa o AWS SDK real contra `S3StubServer`, grava e reabre bytes originais e confere tamanho, digest, headers de metadados e reserva temporária liberada.

```java
@Test
void storesAndReadsTheOriginalBytesThroughTheS3Client() throws Exception {
    String key = "00000000-0000-0000-0000-000000000003";
    byte[] bytes = "original-import".getBytes(StandardCharsets.UTF_8);
    try (var fixture = R2StoreFixture.start(root)) {
        StoredImportFile stored = fixture.store().store(new ByteArrayInputStream(bytes), 1024, key);
        assertThat(stored.bytes()).isEqualTo(bytes.length);
        assertThat(stored.sha256()).isEqualTo(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes)));
        try (var input = fixture.store().open(key)) {
            assertThat(input.readAllBytes()).containsExactly(bytes);
        }
        assertThat(fixture.server().storedBody("/test-imports/imports/" + key)).containsExactly(bytes);
        assertThat(fixture.temporary().reservedBytes()).isZero();
    }
}
```

- [x] **3.2 — Executar vermelho:** `./mvnw.cmd test "-Dtest=R2ImportFileStoreTest,ImportStorageConfigurationTest"` falhou inicialmente por API ainda inexistente; depois da implementação, os testes exercitaram o comportamento do SDK.
- [x] **3.3 — Fixar dependências e construir o cliente.** AWS SDK 2.57.0 via BOM, apenas S3 e URL Connection Client; `dependency:tree` confirmou ausência de clientes Apache e Netty. O cliente usa credenciais estáticas, região `auto`, path-style, checksums somente quando exigidos e HTTPS obrigatório em prod.

```java
S3Client.builder()
        .endpointOverride(endpoint)
        .region(Region.of("auto"))
        .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
        .serviceConfiguration(S3Configuration.builder()
                .pathStyleAccessEnabled(true).chunkedEncodingEnabled(false).build())
        .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
        .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
        .httpClientBuilder(UrlConnectionHttpClient.builder()
                .connectionTimeout(Duration.ofMillis(3000))
                .socketTimeout(Duration.ofMillis(5000)));
```

Os nomes `endpoint`, `accessKeyId` e `secretAccessKey` acima são valores das propriedades validadas. Completar o builder com o timeout de tentativa/operação e a política de no máximo duas tentativas, usando os valores configurados. Registrar um scheduler daemon único para deadlines de stream, com desligamento junto do cliente e remoção de tarefas canceladas da fila (`setRemoveOnCancelPolicy(true)`). Não criar scheduler por arquivo.

- [x] **3.4 — Implementar gravação sem acumular o arquivo em RAM.** Inicia deadline monotônico, reserva `createUpload(limit)`, copia em buffer de 8 KiB, calcula SHA-256 e usa `RequestBody.fromFile`, PUT condicional e metadados de operação. HEAD dentro do deadline só reconhece metadados correspondentes; outra operação gera colisão, sem DELETE. O UUID permanece estável nos retries e o temporário fecha em todas as saídas.
- [x] **3.5 — Implementar GET/DELETE e tradução de erro.** DELETE repetido é idempotente; GET ausente vira integridade `MISSING`; erros do provedor e transporte viram indisponibilidade genérica. `DeadlineInputStream` agenda abort para o prazo restante, cancela o watchdog ao fechar e não drena o corpo. Testes cobrem body atrasado e fechamento antecipado.
- [x] **3.6 — Selecionar backend e preservar local.** `app.import.storage.backend=local|r2` ativa exatamente um store; validação R2 ocorre só ao selecionar R2 e não faz chamadas de inicialização. Endpoint prod exige HTTPS e não aceita userinfo/query/fragment; credenciais não aparecem em mensagens. Os prazos e tentativas têm propriedades validadas. Storage local mantém escrita atômica no mesmo diretório; chave inválida e artefato ausente têm erros distintos.
- [x] **3.7 — Executar matriz de falhas e commit.** Os testes focados passaram 20 casos, sem falhas/erros, com um skip de symlink indisponível no Windows. Cobrem limites, leitura/gravação, colisão, resposta perdida, ausência, 403/429/503, timeout do corpo, fechamento sem drenar, local/R2 e ausência de chamadas no startup. A árvore AWS SDK não inclui clientes Apache/Netty. Commit `6ffa7e45`: `feat(import): add private R2 storage adapter`.

**Saída verificável:** cliente S3 real contra servidor controlado; armazenamento sem overwrite, sem arquivo inteiro no heap e sem dependência de conta externa.

## Tarefa 4: Referência verificável e sessão de materialização

**Depende de:** tarefa 3.

**Files:**
- Create: `src/main/java/com/watchwise/watchwise_api/platformimport/model/ImportArtifactReference.java`
- Create: `src/main/java/com/watchwise/watchwise_api/platformimport/storage/ImportArtifactReader.java`
- Create: `src/main/java/com/watchwise/watchwise_api/platformimport/storage/ReadableImportArtifact.java`
- Create: `src/test/java/com/watchwise/watchwise_api/platformimport/storage/ImportArtifactReaderTest.java`

**Interfaces:**

```java
public record ImportArtifactReference(String key, long bytes, String sha256) {}
public final class ImportArtifactReader {
    public ImportArtifactReader(ImportFileStore store, ImportTemporaryFiles temporary, ImportLimits limits);
    public ReadableImportArtifact open(ImportArtifactReference reference);
}
public interface ReadableImportArtifact extends AutoCloseable {
    Path path();
    InputStream openStream() throws IOException;
    void close();
}
```

O record valida UUID, tamanho não negativo e digest hexadecimal SHA-256 de 64 caracteres; o reader aplica o limite configurado da aplicação antes de reservar/copiar. `open` retorna apenas depois de verificar o conteúdo completo. O handle fecha também streams filhos que ainda estiverem abertos antes de remover o arquivo.

- [x] **4.1 — Escrever o teste de rejeição antes do parsing.** Os casos com R2 cobrem digest incorreto, tamanhos divergentes e corpo truncado; todos falham antes de entregar um handle.

```java
@Test
void doesNotExposeAnArtifactWhoseDigestDiffersFromTheJob() throws Exception {
    String key = "00000000-0000-0000-0000-000000000004";
    try (var fixture = R2StoreFixture.start(root)) {
        fixture.store().store(new ByteArrayInputStream(new byte[] {1, 2, 3}), 1024, key);
        var reader = new ImportArtifactReader(fixture.store(), fixture.temporary(), new ImportLimits());
        var reference = new ImportArtifactReference(key, 3, "0".repeat(64));
        assertThatThrownBy(() -> reader.open(reference))
                .isInstanceOf(ImportArtifactIntegrityException.class);
        assertThat(fixture.temporary().reservedBytes()).isZero();
    }
}
```

- [x] **4.2 — Executar vermelho:** `./mvnw.cmd test "-Dtest=ImportArtifactReaderTest"` falhou com a implementação temporária que ainda não materializava os artefatos.
- [x] **4.3 — Implementar cópia e verificação.** Reserva arquivo de leitura, copia em buffer de 8 KiB, rejeita bytes além do tamanho esperado e valida contagem/hash completos antes de entregar o handle. Erros de transporte continuam retryable; ausência e corrupção são integridade. Não carrega o arquivo inteiro em memória.

```java
private static long copyAndHash(InputStream input, OutputStream output,
                                MessageDigest digest, long expectedBytes) throws IOException {
    byte[] buffer = new byte[8192];
    long total = 0;
    for (int count; (count = input.read(buffer)) != -1;) {
        if (count == 0) continue;
        if (count > expectedBytes - total) {
            throw new ImportArtifactIntegrityException(ImportArtifactIntegrityException.Reason.CORRUPT, null);
        }
        output.write(buffer, 0, count);
        digest.update(buffer, 0, count);
        total += count;
    }
    return total;
}
```

Depois da cópia, comparar `total` e digest com a referência antes de construir o handle. O caller fecha os streams e o temporário em caso de divergência.
- [x] **4.4 — Implementar uso e fechamento da sessão.** `path()`/`openStream()` falham após `close()`. Cada sessão permite no máximo o limite configurado de streams filhos; eles são removidos ao fechar, e streams restantes são fechados antes do temporário.
- [x] **4.5 — Verde e commit.** Os testes focados passaram 28 casos, sem falhas/erros e um skip de symlink indisponível no Windows. Cobrem tamanho maior/menor, hash incorreto, corpo GET truncado, abandono, exceção do consumidor, fechamento repetido, limite de streams e leituras concorrentes. Uma sessão faz um GET mesmo com múltiplas leituras locais. Commit `7c35318a`: `feat(import): verify materialized import artifacts`.

**Saída verificável:** o consumidor nunca vê arquivo parcial ou sem validação e não depende de um caminho durável local.

## Tarefa 5: Metadados do job e leitores ZIP usando uma sessão por operação

**Depende de:** tarefa 4.

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/platformimport/model/ImportReadContext.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/platformimport/parser/SafeImportReader.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/platformimport/parser/impl/LetterboxdArchiveInspector.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/platformimport/parser/impl/TraktArchiveInspector.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/platformimport/service/impl/LetterboxdArchiveImportHandler.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/platformimport/service/impl/TraktArchiveImportHandler.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/platformimport/service/impl/ImportWorker.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/platformimport/config/ImportModeConfiguration.java`
- Modify: `src/test/java/com/watchwise/watchwise_api/platformimport/parser/SafeImportReaderTest.java`
- Modify: `src/test/java/com/watchwise/watchwise_api/platformimport/parser/LetterboxdExportParserTest.java`
- Modify: `src/test/java/com/watchwise/watchwise_api/platformimport/parser/TraktExportParserTest.java`
- Modify: `src/test/java/com/watchwise/watchwise_api/platformimport/controller/PlatformArchiveImportIntegrationTest.java`
- Modify: todos os construtores de `ImportReadContext` localizados pela busca abaixo, incluindo fixtures de integração.

**Interfaces:**

```java
public record ImportReadContext(UUID ownerId, UUID jobId, String storageKey,
                                String sourceNamespace, ZoneId timeZone,
                                long expectedBytes, String expectedSha256) {
    public ImportArtifactReference artifact() {
        return new ImportArtifactReference(storageKey, expectedBytes, expectedSha256);
    }
}
// SafeImportReader conserva limites e validação ZIP, sem um store no construtor.
public SafeImportReader(ImportLimits limits);
public List<String> entryNames(ReadableImportArtifact artifact);
public void forEachEntry(ReadableImportArtifact artifact, BiConsumer<String, InputStream> consumer);
// Inspectors recebem a sessão, sem abri-la ou fechá-la.
public ImportInventory inspect(ImportReadContext context, ReadableImportArtifact artifact);
```

- [x] **5.1 — Adicionar o caso de duas passagens sobre o mesmo ZIP.** `SafeImportReaderTest` valida nomes e entradas por um único GET. `TraktExportParserTest` verifica a ordenação natural e um inspector Trakt de duas passagens, também com apenas um GET.

```java
@Test
void readsNamesAndEntriesWithOneRemoteDownload() throws Exception {
    var bytes = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(bytes)) {
        zip.putNextEntry(new ZipEntry("watchlist.csv"));
        zip.write("Name,Year\nMovie,2020\n".getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
    String key = "00000000-0000-0000-0000-000000000005";
    try (var fixture = R2StoreFixture.start(root)) {
        byte[] content = bytes.toByteArray();
        var stored = fixture.store().store(new ByteArrayInputStream(content), 1024 * 1024, key);
        var reference = new ImportArtifactReference(key, stored.bytes(), stored.sha256());
        var artifactReader = new ImportArtifactReader(fixture.store(), fixture.temporary(), new ImportLimits());
        var safeReader = new SafeImportReader(new ImportLimits());
        try (var artifact = artifactReader.open(reference)) {
            List<String> names = safeReader.entryNames(artifact);
            var consumed = new ArrayList<String>();
            safeReader.forEachEntry(artifact, (name, input) -> consumed.add(name));
            assertThat(consumed).containsAll(names);
        }
        assertThat(fixture.server().requestCount("GET", "/test-imports/imports/" + key)).isEqualTo(1);
    }
}
```

Não usar digest constante inventado para um arquivo persistido. Acrescentar também o caso específico de inspeção Trakt com seus nomes/páginas reais, usando as fixtures da suíte existente.

- [x] **5.2 — Executar vermelho:** `./mvnw.cmd test "-Dtest=SafeImportReaderTest,TraktExportParserTest,LetterboxdExportParserTest"` falhou inicialmente ao compilar chamadas novas de sessão e o novo contexto com metadata.
- [x] **5.3 — Transportar metadados reais.** `ClaimedJob`, SQL da reivindicação e mapeamento agora incluem `upload_size_bytes`/`zip_digest`; o worker preenche o contexto antes de I/O. Contextos sintéticos usam tamanho zero e digest SHA-256 do conteúdo vazio. Não há construtor legado sem metadata.
- [x] **5.4 — Refatorar ZIP preservando suas defesas.** `SafeImportReader` recebe somente `ImportLimits` e uma sessão verificada. Mantém as duas bibliotecas, validações de nomes/CRC/limites e streaming expandido. Aquisição R2 fica fora do tratamento de I/O ZIP.
- [x] **5.5 — Abrir/fechar no handler.** Os handlers Letterboxd e Trakt materializam o artefato em cada inspect/normalize, passam o mesmo handle ao inspector e reader e fecham a sessão no fim. O inspector Trakt usa uma sessão para ambas as passagens.
- [x] **5.6 — Verde e commit.** Passaram 43 testes de parser, 9 de integração de arquivo e 17 testes do worker/PostgreSQL, sem falhas, erros ou skips. A busca não encontrou `PrivateImportFileStore`/`pathForReading` nos parsers ou handlers ZIP. Commit `f65161f4`: `refactor(import): read archives from verified artifact sessions`.

**Saída verificável:** inspeção e normalização ZIP independem do backend; cada operação baixa uma vez e encerra o arquivo local.

## Tarefa 6: CSV, JSON e Picks-template com a mesma fronteira de leitura

**Depende de:** tarefa 5.

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/platformimport/service/impl/SingleListImportHandler.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/platformimport/service/impl/SingleWatchlistImportHandler.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/platformimport/service/impl/PicksTemplateImportHandler.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/platformimport/config/ImportModeConfiguration.java`
- Modify: `src/test/java/com/watchwise/watchwise_api/platformimport/service/SingleFileItemHandlerTest.java`
- Modify: `src/test/java/com/watchwise/watchwise_api/platformimport/controller/SingleFileImportControllerIntegrationTest.java`
- Modify: `src/test/java/com/watchwise/watchwise_api/platformimport/controller/StandaloneCollectionImportIntegrationTest.java`
- Modify: `src/test/java/com/watchwise/watchwise_api/platformimport/service/PicksTemplateImportIntegrationTest.java`
- Modify: `src/test/java/com/watchwise/watchwise_api/platformimport/service/ImportTemplateUploadLimitTest.java`

**Interfaces:**
- Os handlers conservam `inspect(ImportReadContext)` e `normalize(ImportReadContext, ImportSelection, Consumer<NormalizedImportRecord>)`.
- Substituir o parâmetro de construtor `ImportFileStore` por `ImportArtifactReader`. Usar `context.artifact()` e `ReadableImportArtifact.openStream()`.
- Helpers que detectam o formato passam a receber `ReadableImportArtifact`; podem abrir mais de um stream local da mesma sessão, sem novo download.

- [x] **6.1 — Adicionar um teste que detecte armazenamento indisponível sendo convertido em arquivo inválido.** `SingleFileItemHandlerTest` cobre lista, watchlist e falha durante a detecção Trakt; `PicksTemplateImportStorageFailureTest` confirma propagação da indisponibilidade no handler Picks.

```java
@Test
void preservesStorageFailureDuringStandaloneNormalization() {
    var artifacts = mock(ImportArtifactReader.class);
    var context = new ImportReadContext(UUID.randomUUID(), UUID.randomUUID(),
            "00000000-0000-0000-0000-000000000006", "trakt", ZoneId.of("UTC"), 3, "0".repeat(64));
    var failure = new ImportStorageUnavailableException(new IOException("remote unavailable"));
    when(artifacts.open(context.artifact())).thenThrow(failure);
    var handler = new SingleWatchlistImportHandler(ImportSourceFormat.TRAKT_JSON,
            artifacts, mock(TraktItemParser.class), mock(LetterboxdItemCsvParser.class));
    var selection = new ImportSelection(false, true, Set.of(), null);
    assertThatThrownBy(() -> handler.normalize(context, selection, record -> {})).isSameAs(failure);
}
```

O mock serve aqui para injetar uma falha; o assert verifica a classificação mantida pelo handler. Os demais testes desta tarefa devem processar arquivos reais, não apenas verificar chamadas do mock.

- [x] **6.2 — Executar vermelho:** `./mvnw.cmd test "-Dtest=SingleFileItemHandlerTest,ImportTemplateUploadLimitTest"` falhou inicialmente ao compilar os construtores que passaram a receber `ImportArtifactReader`.
- [x] **6.3 — Implementar uma sessão externa por operação que lê a fonte.** Cada `normalize` abre uma sessão; `SingleWatchlistImportHandler.inspect` não baixa arquivo. Os helpers Letterboxd/Trakt reabrem apenas streams locais do mesmo artefato. Validação do upload HTTP segue lendo o stream recebido diretamente.

```java
try (var artifact = artifacts.open(context.artifact())) {
    try (InputStream input = artifact.openStream()) {
        traktParser.parse(input, context, "watchlist.json", ImportRecordKind.WATCHLIST_ITEM, sink);
    }
}
```

Este bloco pertence ao ramo Trakt do handler de watchlist; o ramo CSV mantém seu parser atual. Métodos de parsing podem lançar erro de conteúdo, mas a aquisição do artefato não deve entrar num catch genérico que o reclassifique como 400.

- [x] **6.4 — Corrigir também a inspeção de formato.** A detecção agora retorna `false` somente para conteúdo estruturalmente diferente ou JSON malformado; falhas de I/O são traduzidas em indisponibilidade e não são tratadas como outro formato.
- [x] **6.5 — Implementação e commit.** Passaram 39 testes focados, cinco integrações de upload individual e 17 integrações Picks. Quatro fluxos de `StandaloneCollectionImportIntegrationTest` passaram. A classe completa ainda tem dois erros em `UserListServiceImpl.deleteUserList`, fora do caminho de storage: exclusões deixam `UserListItem.userList` transiente no flush. O teste de 5.000 opções Picks foi atualizado para 200, conforme o limite vigente do parser (50 categorias/200 opções). Busca confirmou que os handlers não usam mais `store.open(context.storageKey())`. Commit `beb85335`: `refactor(import): materialize standalone import sources`.

**Saída verificável:** todos os formatos obtêm bytes pela mesma fronteira de integridade/limites, preservando seus contratos de conteúdo.

## Tarefa 7: Consistência de quota, retries e confirmação segura da limpeza

**Depende de:** tarefas 3–6.

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/platformimport/service/ImportUploadQuotaService.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/platformimport/service/impl/ImportUploadQuotaServiceImpl.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/platformimport/service/impl/ImportJobServiceImpl.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/platformimport/service/impl/ImportWorker.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/platformimport/service/impl/ImportCleanupJob.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/common/exception/GlobalExceptionHandler.java` somente se o handler existente não cobrir o contrato abaixo.
- Modify: `src/test/java/com/watchwise/watchwise_api/platformimport/service/ImportWorkerIntegrationTest.java`
- Modify: `src/test/java/com/watchwise/watchwise_api/platformimport/service/ImportRestartIntegrationTest.java`
- Modify: `src/test/java/com/watchwise/watchwise_api/platformimport/controller/ImportControllerIntegrationTest.java`
- Modify: `src/test/java/com/watchwise/watchwise_api/common/exception/GlobalExceptionHandlerTest.java`

**Interfaces:**
- Adicionar `void markStorageCollision(UUID ownerId, UUID jobId)` ao serviço de quota; somente colisão comprovada usa esse método.
- Manter `markFailed`, `markStored`, `reactivate` e a fila de limpeza existentes.
- A projeção interna de cleanup passa a incluir `claimedUntil`, retornado pelo banco; confirmação e adiamento usam esse valor na condição SQL.

- [x] **7.1 — Escrever vermelho de retry em fase de leitura.** `ImportWorkerIntegrationTest` testa outage em `INSPECT` e `NORMALIZE` pela fronteira do registry e integridade corrompida em `INSPECT`. Os helpers usam metadata sintética válida para conteúdo vazio.

```java
@Test
void retriesStorageOutageWithoutDiscardingTheWorkPhase() {
    User owner = createUser();
    ImportJob job = createInspectionJob(owner.getId());
    String phase = job.getWorkPhase();
    doThrow(new ImportStorageUnavailableException(new IOException("remote unavailable")))
            .when(registry).inspect(any(ImportModeKey.class), any(ImportReadContext.class));
    worker.runOnce(1);
    ImportJob reloaded = jobRepository.findById(job.getId()).orElseThrow();
    assertThat(reloaded.isWorkPending()).isTrue();
    assertThat(reloaded.getWorkPhase()).isEqualTo(phase);
    assertThat(reloaded.getNextRunAt()).isNotNull();
}
```

O getter existente da entidade é `isWorkPending()`. Acrescentar teste equivalente no caminho de `normalize`, para não cobrir apenas a primeira fase.

- [x] **7.2 — Executar vermelho:** os três testes novos do worker falharam antes da classificação específica e foram confirmados contra PostgreSQL/Testcontainers.
- [x] **7.3 — Corrigir a classificação do worker.** Outage agenda retry não terminal preservando fase/checkpoint. Ausência/corrupção termina o job deterministamente em `INSPECT` ou `NORMALIZE`; `ImportUnitTransaction.failDeterministically` agora cobre ambas as fases e mantém a regra de record-limit.

```java
} catch (ImportStorageUnavailableException exception) {
    unitTransaction.scheduleRetry(context, FAILURE_BACKOFF, false);
} catch (ImportArtifactIntegrityException exception) {
    unitTransaction.failDeterministically(context);
}
```

Os outros catches atuais continuam na ordem que preserva suas regras; não remover a regra específica de TMDB ou de espera por outro job.

- [x] **7.4 — Distinguir falha incerta de colisão no upload.** Falhas incertas mantêm `markFailed` e fila de compensação. Colisão confirmada usa `markStorageCollision`, marca FAILED, zera quota/digest/fase e não cria cleanup; os stores local/R2 não apagam nem sobrescrevem o objeto colidente.
- [x] **7.5 — Validar o retry antes de reativar.** O serviço materializa e valida tamanho/hash antes de chamar `reactivate`, fora de transação. Outage retorna 503 sem mudar o job; artefato confirmado ausente/corrompido retorna 500 genérico. `reactivate` revalida estado, fase, expiração e quota sob lock.
- [x] **7.6 — Proteger ack/defer da limpeza.** A reivindicação retorna `ready_at` como token de posse. Ack só remove a tarefa e zera bytes quando ID, key e token correspondem; defer também exige o token. DELETE remoto continua fora da transação. Teste de ack atrasado confirma que uma claim substituta e sua quota permanecem intactas.

```sql
DELETE FROM import_file_cleanup_tasks
WHERE id = ? AND ready_at = ?
```

O UPDATE de bytes do job ocorre na mesma transação somente quando esse DELETE afetar uma linha. O adiamento da tarefa também exige `WHERE id = ? AND ready_at = ?`, em vez de atualizar apenas por ID.

- [x] **7.7 — Verde e commit.** As suítes do worker, controller, restart e `GlobalExceptionHandlerTest` passaram 62 testes, sem falhas ou skips. Respostas 503/500 são genéricas; a collision não apaga o arquivo nem agenda cleanup. Commit `b6ebd212`: `fix(import): preserve storage failures and cleanup ownership`.

**Saída verificável:** falhas externas não viram arquivo inválido, não duplicam fatos e não liberam quota ou apagam objeto sem confirmação apropriada.

## Tarefa 8: Profile prod, datasource nativo, pool e intervalo do worker

**Depende de:** tarefas 1–7.

**Files:**
- Modify: `src/main/resources/application.properties`
- Modify: `src/main/resources/application-dev.properties`
- Modify: `src/main/resources/application-prod.properties`
- Modify: `src/main/java/com/watchwise/watchwise_api/platformimport/service/impl/ImportWorker.java` apenas para validar intervalo, se a configuração agendada atual não o fizer.
- Create: `src/main/java/com/watchwise/watchwise_api/common/config/ProductionDataSourcePolicy.java`
- Create: `src/main/java/com/watchwise/watchwise_api/platformimport/config/ImportWorkerProperties.java`
- Create: `src/test/java/com/watchwise/watchwise_api/common/config/ProductionDataSourcePolicyTest.java`
- Create: `src/test/java/com/watchwise/watchwise_api/common/config/ProductionConfigurationTest.java`
- Modify: `src/test/java/com/watchwise/watchwise_api/dailygame/config/DailyGameProductionConfigurationTest.java`

**Interfaces:**
- `ProductionDataSourcePolicy.validate(HikariConfig)` valida o datasource final apenas em prod. Integrá-lo ao ciclo do bean antes do primeiro uso; não criar outro pool.
- O binding de URL/usuário/senha continua sendo o nativo `SPRING_DATASOURCE_*` do Boot/buildpack.
- Validar o intervalo por propriedade tipada ou bean de configuração existente, antes de o scheduler iniciar. Não criar um segundo método agendado.

`ImportWorkerProperties` é o binding validado sob `app.import.worker`, com `fixedDelayMs=1000` e valor mínimo um. O `@Scheduled` existente continua lendo a mesma propriedade; os testes verificam que os dois caminhos recebem o mesmo override.

- [x] **8.1 — Escrever vermelho para TLS efetivo.** Os testes rejeitam `sslmode=disable` na URL mesmo com `sslmode=require` em datasource properties.

```java
@Test
void rejectsUrlThatOverridesRequiredEncryption() {
    var config = new HikariConfig();
    config.setJdbcUrl("jdbc:postgresql://localhost/watchwise?sslmode=disable");
    config.addDataSourceProperty("sslmode", "require");
    assertThatThrownBy(() -> ProductionDataSourcePolicy.validate(config))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("sslmode");
}
```

- [x] **8.2 — Executar vermelho:** os testes foram executados antes da política e das propriedades existirem e falharam por binding/classes ausentes; após a implementação foram executados novamente.
- [x] **8.3 — Completar as propriedades de produção.** `application-prod.properties` agora ativa PORT, JPA/Flyway, cookies/CORS, TLS/pool, Resend/TMDB, storage R2 e temporários; traz defaults funcionais de rate limit e jobs. `application.properties` usa profile default dev e `application-dev.properties` declara local/1000 ms.

```properties
server.port=${PORT:8080}
spring.docker.compose.enabled=false
spring.flyway.enabled=true
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.open-in-view=false
spring.jpa.show-sql=false
spring.jpa.properties.hibernate.format_sql=false
spring.datasource.driver-class-name=org.postgresql.Driver
spring.datasource.hikari.maximum-pool-size=${DB_POOL_MAX_SIZE:8}
spring.datasource.hikari.minimum-idle=${DB_POOL_MIN_IDLE:2}
spring.datasource.hikari.connection-timeout=${DB_POOL_CONNECTION_TIMEOUT_MS:5000}
spring.datasource.hikari.data-source-properties.reWriteBatchedInserts=true
spring.datasource.hikari.data-source-properties.sslmode=require
app.cookies.secure=true
app.jwt.secret=${JWT_SECRET}
app.cors.allowed-origins=${CORS_ALLOWED_ORIGINS}
app.tmdb.api-key=${APP_TMDB_API_KEY}
app.oauth.google.client-id=${GOOGLE_OAUTH_CLIENT_ID}
app.import.storage.backend=${WATCHWISE_IMPORT_STORAGE_BACKEND:r2}
app.import.worker.fixed-delay-ms=${IMPORT_WORKER_FIXED_DELAY_MS:5000}
```

Adicionar também as seguintes ligações, com validação condicional ao backend para os campos R2. O staging em branco seleciona a pasta multipart do diretório de execução; não deve virar um `Path` vazio interpretado como diretório de trabalho.

```properties
app.import.storage-directory=${WATCHWISE_IMPORT_STORAGE_DIRECTORY:}
app.import.r2.endpoint=${WATCHWISE_R2_ENDPOINT:}
app.import.r2.bucket=${WATCHWISE_R2_BUCKET:}
app.import.r2.access-key-id=${WATCHWISE_R2_ACCESS_KEY_ID:}
app.import.r2.secret-access-key=${WATCHWISE_R2_SECRET_ACCESS_KEY:}
app.import.r2.region=auto
app.import.r2.connection-timeout-ms=${WATCHWISE_R2_CONNECTION_TIMEOUT_MS:3000}
app.import.r2.socket-timeout-ms=${WATCHWISE_R2_SOCKET_TIMEOUT_MS:5000}
app.import.r2.attempt-timeout-ms=${WATCHWISE_R2_ATTEMPT_TIMEOUT_MS:10000}
app.import.r2.operation-timeout-ms=${WATCHWISE_R2_OPERATION_TIMEOUT_MS:20000}
app.import.r2.max-attempts=${WATCHWISE_R2_MAX_ATTEMPTS:2}
app.import.temporary-directory=${WATCHWISE_IMPORT_TEMPORARY_DIRECTORY:${java.io.tmpdir}/watchwise-import-work}
app.import.staging-directory=${WATCHWISE_IMPORT_STAGING_DIRECTORY:}
app.import.max-concurrent-uploads-per-process=${WATCHWISE_IMPORT_MAX_CONCURRENT_UPLOADS:2}
app.import.max-concurrent-artifact-reads-per-process=${WATCHWISE_IMPORT_MAX_CONCURRENT_ARTIFACT_READS:2}
spring.servlet.multipart.max-file-size=50MB
spring.servlet.multipart.max-request-size=51MB
```

Retirar a ligação anterior de `spring.servlet.multipart.location` para que o bean de multipart aponte ao diretório de execução real. Não declarar `spring.datasource.url=${DATABASE_URL}` nem um conversor customizado. Retirar os antigos placeholders `DB_*` do template prod. Preservar Resend e suas variáveis existentes. Local exige diretório durável somente quando selecionado. Não fixar o orçamento final de caches nesta tarefa: sua ligação ao profile será concluída na tarefa 11.

- [x] **8.4 — Validar datasource sem abrir outra conexão.** A política inspeciona propriedades efetivas via `DriverManager.getDriver(...).getPropertyInfo(...)`, valida TLS, batch rewrite e limites Hikari sem imprimir URL/credenciais, e roda como `BeanPostProcessor` apenas em prod. Ativação simultânea de dev/prod falha.
- [x] **8.5 — Verificar binding e propriedades obrigatórias.** Testes cobrem `SPRING_DATASOURCE_*` com senha contendo `+`, valores fictícios JWT/CORS/TMDB/Resend e placeholder ausente sem ecoar valor. A política e seleção R2/local não fazem conexões a banco, R2 ou Google. Isso não comprova handshake TLS real contra o Heroku.
- [x] **8.6 — Validar agendamentos e commit.** Delay prod 5000 ms, override e rejeição de zero/negativo; dev preserva 1000 ms e geração diária dos jogos a cada dez minutos. `Production*`, `DailyGameProductionConfigurationTest` e `ImportStorageConfigurationTest` passaram 17 testes. Commit `468d6111`: `feat(config): configure Heroku production runtime`.

**Saída verificável:** configuração de produção usa o buildpack, um único pool limitado e propriedades completas, sem depender de provisionamento para ser desenvolvida.

## Tarefa 9: Propriedades e repartição coordenada do orçamento de caches

**Depende de:** tarefa 8; não exige R2 real.

**Files:**
- Create: `src/main/java/com/watchwise/watchwise_api/common/cache/CacheMemoryProperties.java`
- Create: `src/main/java/com/watchwise/watchwise_api/common/cache/CacheBudgetAllocator.java`
- Create: `src/main/java/com/watchwise/watchwise_api/common/cache/CacheMemoryConfiguration.java`
- Create: `src/test/java/com/watchwise/watchwise_api/common/cache/CacheBudgetAllocatorTest.java`
- Create: `src/test/java/com/watchwise/watchwise_api/common/cache/CacheMemoryPropertiesTest.java`

**Interfaces:**

```java
public record CacheMemoryProperties(Mode mode, Long maxEstimatedBytes,
                                    long mediaMaxEstimatedBytes, Map<String, Integer> shares) {
    public enum Mode { ENTRIES, WEIGHTED, DISABLED }
}
public final class CacheBudgetAllocator {
    public static final String MEDIA_CACHE = "dailyGameMedia";
    public Map<String, Long> allocate(CacheMemoryProperties properties);
}
```

O mapa retornado no modo weighted contém os vinte nomes de beans abaixo e `dailyGameMedia`. Nos outros modos, o alocador retorna mapa vazio e o factory da tarefa 11 decide a política legada/desativada.

```text
tmdbMovieFullDetailsCache, tmdbCardMetadataCache, tmdbTvFullDetailsCache,
tmdbTvDisplayDetailsCache, tmdbSeasonFullDetailsCache, tmdbEpisodeFullDetailsCache,
tmdbEpisodeImagesCache, tmdbPersonAggregateCache, tmdbPersonDetailsCache,
tmdbMovieReleaseDatesCache, tmdbCalendarSeasonDetailsCache,
tmdbMovieSearchCache, tmdbTvSearchCache, tmdbPersonSearchCache, tmdbMultiSearchCache,
tmdbMovieDiscoveryCache, tmdbTvDiscoveryCache, tmdbTrendingMovieCache,
tmdbTrendingTvCache, tmdbTvContentRatingsCache
```

- [x] **9.1 — Escrever o teste com orçamento pequeno artificial.** 1000/200 bytes são apenas valores de teste para provar aritmética, não recomendação para produção.

```java
@Test
void partitionsMetadataAndMediaWithoutMultiplyingTheTotal() {
    var properties = new CacheMemoryProperties(CacheMemoryProperties.Mode.WEIGHTED,
            1000L, 200L, Map.of());
    Map<String, Long> budgets = new CacheBudgetAllocator().allocate(properties);
    assertThat(budgets).hasSize(21);
    assertThat(budgets.get("dailyGameMedia")).isEqualTo(200L);
    assertThat(budgets.get("tmdbMovieSearchCache")).isEqualTo(40L);
    assertThat(budgets.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(1000L);
}
```

- [x] **9.2 — Executar vermelho:** `./mvnw.cmd test "-Dtest=CacheBudgetAllocatorTest,CacheMemoryPropertiesTest"`.
- [x] **9.3 — Implementar binding e validação.** Prefixo `app.cache.memory`, modo default entries fora de prod. Em weighted, total obrigatório, media > 0 e media < total. Rejeitar orçamento por cache >= `Integer.MAX_VALUE`, parcela zero, participação zero/negativa, nome desconhecido e soma/conta com overflow. O padrão de media é 52428800; tests de orçamento menor devem sobrescrevê-lo explicitamente. Não converter total ausente em zero silenciosamente.
- [x] **9.4 — Implementar alocação.** Subtrair media do total, preencher participação 1 para nomes omitidos e calcular `floor(metadataBudget * share / sumShares)`. Usar `BigInteger` na multiplicação/divisão ou aritmética exata equivalente para evitar overflow intermediário. A sobra permanece sem alocação. Copiar a configuração e o resultado para mapas imutáveis. Nenhum cache recebe novamente o total completo.
- [x] **9.5 — Verde e commit.** Cobrir divisão com sobra, participações desiguais, total ausente nos três modos, limites numéricos e nome duplicado/desconhecido. Commit: `feat(cache): allocate shared estimated memory budgets`.

**Saída verificável:** existe um orçamento único verificável e não vinte limites independentes que multiplicam a memória pretendida.

## Tarefa 10: Estimador de entradas com custo e tamanho limitados

**Depende de:** tarefa 9.

**Files:**
- Create: `src/main/java/com/watchwise/watchwise_api/common/cache/CacheEntryWeigher.java`
- Create: `src/test/java/com/watchwise/watchwise_api/common/cache/CacheEntryWeigherTest.java`

**Interfaces:** `public int weight(Object key, Object value, long cacheBudget)`. `cacheBudget` é positivo e menor que `Integer.MAX_VALUE`; peso acima dele significa entrada não retida.

- [x] **10.1 — Escrever vermelho para crescimento e corte.** O teste define seu próprio record de dados; o estimador deve permitir records de dados confiáveis da aplicação e records equivalentes desse pacote de teste, sem percorrer campos de entidades ou serviços.

```java
record Payload(List<String> names) {}

@Test
void weighsPayloadGrowthAndRejectsValuesLargerThanTheirCache() {
    var weigher = new CacheEntryWeigher();
    int small = weigher.weight("key", new Payload(List.of("a")), 100000);
    int large = weigher.weight("key", new Payload(List.of("a".repeat(1000))), 100000);
    assertThat(small).isPositive();
    assertThat(large).isGreaterThan(small);
    assertThat(weigher.weight("key", new byte[4096], 1024)).isGreaterThan(1024);
}
```

- [x] **10.2 — Executar vermelho:** `./mvnw.cmd test "-Dtest=CacheEntryWeigherTest"`.
- [x] **10.3 — Implementar estimativa estrutural.** Somar chave, valor e overhead da entrada. Usar aproximações explícitas, alinhadas para oito bytes: entrada 64; String `48 + 2*length`; byte[] `16 + length`; array de referências `16 + 8*length`; lista `40 + 8*size` mais filhos; mapa `64 + 48*size` mais chaves/valores; record `32 + 8*componentCount` mais componentes; Optional 24 mais valor; enums e escalares suportados 32. UUID e tipos `java.time` usados pelos DTOs têm custo fixo documentado. Suportar explicitamente `MediaType` com overhead 64 e peso de tipo, subtipo e parâmetros obtidos por seus acessores públicos: sem isso, o `DailyGameMediaResponseDTO` cairia sempre no fallback de tipo não suportado e nunca seria retido. São estimativas, não medições de layout da JVM.
- [x] **10.4 — Limitar custo e evitar pesos incorretos.** Limites iniciais do estimador: profundidade 32 e 10000 nós visitados. Saturar somas em `cacheBudget + 1`; manter cálculo em long até o cast final seguro. Detectar ciclos por identidade no caminho ativo; referência compartilhada pode ser contada novamente de forma conservadora. Examinar somente records de dados e tipos conhecidos; tipo não suportado, acesso inacessível ou limites excedidos retornam peso de rejeição. Não serializar para byte[], não usar instrumentation e não inspecionar entidades JPA. Guardar metadados de record em `ClassValue` para evitar mapa global ilimitado de classes.
- [x] **10.5 — Verificar todos os tipos usados.** Incluir `TmdbLookupResult.Found`, `NotFound`, `Unavailable`, `TmdbSearchCacheKey`, páginas/DTOs TMDB e valores de imagens na matriz de testes. Resultados vazios têm peso > 0. Arrays não podem ser percorridos sem limite quando seu próprio header já excedeu o orçamento. Simular lista cíclica, aninhamento excessivo e objeto de serviço desconhecido: a chamada não lança nem retém o valor como peso um.
- [x] **10.6 — Verde e commit.** Rodar `CacheEntryWeigherTest`; confirmar aumento com payload, saturação sem overflow e nenhuma cópia de imagem para pesá-la. Commit: `feat(cache): estimate bounded cache entry weights`.

**Saída verificável:** o estimador não cria outro grande consumo de memória/CPU nem deixa tipos desconhecidos ocuparem espaço sem contabilização.

## Tarefa 11: Aplicar as políticas aos vinte caches TMDB e às imagens

**Depende de:** tarefas 9–10.

**Files:**
- Create: `src/main/java/com/watchwise/watchwise_api/common/cache/BudgetedCacheFactory.java`
- Create: `src/main/java/com/watchwise/watchwise_api/common/cache/CacheDiagnostics.java`
- Create: `src/main/java/com/watchwise/watchwise_api/common/cache/CacheSnapshot.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/common/cache/CacheMemoryConfiguration.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbCacheConfig.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameMediaServiceImpl.java`
- Modify: `src/main/resources/application.properties`
- Modify: `src/main/resources/application-dev.properties`
- Modify: `src/main/resources/application-prod.properties`
- Create: `src/test/java/com/watchwise/watchwise_api/common/cache/BudgetedCacheFactoryTest.java`
- Create: `src/test/java/com/watchwise/watchwise_api/common/cache/CacheInventoryIntegrationTest.java`
- Create: `src/test/java/com/watchwise/watchwise_api/common/cache/TestCacheFactory.java`
- Modify: `src/test/java/com/watchwise/watchwise_api/common/tmdb/TmdbCacheConfigTest.java`
- Modify: `src/test/java/com/watchwise/watchwise_api/common/tmdb/TmdbClientCachingTest.java`
- Modify: `src/test/java/com/watchwise/watchwise_api/common/tmdb/TmdbClientDisplayDetailsTest.java`
- Modify: `src/test/java/com/watchwise/watchwise_api/content/service/impl/ContentCardAssemblerTest.java`
- Modify: `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameMediaServiceTest.java`
- Modify: demais testes com construção direta desses dois componentes, identificados por `rg`.

**Interfaces:**

```java
public final class BudgetedCacheFactory {
    public BudgetedCacheFactory(CacheMemoryProperties properties, CacheBudgetAllocator allocator,
                               CacheEntryWeigher weigher, CacheDiagnostics diagnostics);
    public <K, V> Cache<K, V> metadata(String name, Duration ttl, long legacyMaximumEntries);
    public <K, V> Cache<K, V> media(Duration ttl, long legacyMaximumBytes, Weigher<K, V> legacyWeigher);
}
public final class CacheDiagnostics {
    public List<CacheSnapshot> snapshot();
}
public record CacheSnapshot(String name, CacheMemoryProperties.Mode mode,
                            OptionalLong maximumWeight, long entries,
                            OptionalLong estimatedRetainedWeight,
                            long hits, long misses, long evictions) {}
```

O factory registra os caches no diagnóstico ao criá-los. `media` usa sempre o nome `dailyGameMedia`. `TestCacheFactory`, somente em testes, tem métodos estáticos `entries()`, `disabled()` e `weighted(long total, long media)`, construindo os quatro colaboradores reais; não acrescentar construtor de produção que esconda configuração só para manter testes antigos.

- [x] **11.1 — Escrever vermelho para entrada grande e ausência de retenção.**

```java
@Test
void weightedCacheDoesNotRetainAnEntryThatExceedsItsOwnShare() {
    var factory = TestCacheFactory.weighted(40000, 20000);
    Cache<String, byte[]> cache = factory.metadata("tmdbMovieSearchCache", Duration.ofMinutes(10), 10000);
    cache.put("too-large", new byte[4096]);
    cache.cleanUp();
    assertThat(cache.getIfPresent("too-large")).isNull();
}

@Test
void disabledCacheExecutesItsLoaderWithoutKeepingTheResult() {
    Cache<String, String> cache = TestCacheFactory.disabled()
            .metadata("tmdbMovieSearchCache", Duration.ofMinutes(10), 10000);
    var loads = new AtomicInteger();
    cache.get("key", key -> "value-" + loads.incrementAndGet());
    cache.cleanUp();
    cache.get("key", key -> "value-" + loads.incrementAndGet());
    assertThat(loads).hasValue(2);
}
```

- [x] **11.2 — Executar vermelho:** `./mvnw.cmd test "-Dtest=BudgetedCacheFactoryTest,CacheInventoryIntegrationTest"`.
- [x] **11.3 — Implementar os três modos sem misturar builders.** Entries: manter `maximumSize` para metadados e `maximumWeight`/weigher legado de imagens. Weighted: `maximumWeight(parcela)` e o estimador estrutural em todos; jamais chamar `maximumSize` no mesmo builder. Disabled: `maximumSize(0)` em todos os caches abrangidos, sem retenção, preservando loaders e tratamento de falhas. Aplicar TTL atual e `recordStats()` em todos os modos.

```java
Caffeine.newBuilder()
        .maximumWeight(budget)
        .weigher((K key, V value) -> weigher.weight(key, value, budget))
        .expireAfterWrite(ttl)
        .recordStats()
        .build();
```

Este builder é exclusivamente do ramo weighted. `budget`, `ttl` e `weigher` são os valores alocados e colaboradores do factory, não constantes repetidas por cache.

- [x] **11.4 — Ligar os consumidores.** Injetar o factory em `TmdbCacheConfig`, conservando os nomes de seus vinte beans e seus executores. Cada cache informa seu nome, TTL e limite legado ao factory. Em `DailyGameMediaServiceImpl`, mover a construção do cache para o construtor injetado, usar `.media` e manter emissão/checagem de grants, validade e vínculo com a origem antes de consultar o cache. Preservar o clone defensivo no retorno. Auditar mutações de listas/mapas/arrays dos tipos cacheados; quando houver atualização legítima, substituir a entrada em vez de modificá-la depois de pesada.
- [x] **11.5 — Ligar propriedades e observação.**

```properties
# Prod
app.cache.memory.mode=${WATCHWISE_CACHE_MODE:weighted}
app.cache.memory.max-estimated-bytes=${WATCHWISE_CACHE_MAX_ESTIMATED_BYTES:}
app.cache.memory.media-max-estimated-bytes=${WATCHWISE_CACHE_MEDIA_MAX_ESTIMATED_BYTES:52428800}
```

Em dev/test, o padrão é entries. Disabled não exige o total; weighted exige e valida antes de criar caches. O diagnóstico coleta `policy().eviction()` e `stats()` sem iterar chaves/valores e retorna snapshots imutáveis. Não criar rota HTTP nem ligar monitoramento externo. Log de inicialização pode conter somente nomes, modo e parcelas; não incluir query, usuário ou payload.

- [x] **11.6 — Verificar inventário e regressões.** Em um contexto de teste focado, carregar `TmdbCacheConfig`, `CacheMemoryConfiguration` e `DailyGameMediaServiceImpl` com dependências externas simuladas. Confirmar 21 caches registrados e soma das parcelas <= total. Acrescentar teste de cache quente negando acesso a usuário/grant inválido ou desafio cuja origem mudou. Atualizar construções diretas com `TestCacheFactory`; não preservar um segundo cache oculto de 50 MiB. Rodar as cinco suítes listadas nesta tarefa, incluindo `TmdbClientCachingTest` para a semântica de `NotFound`/`Unavailable`. Commit: `feat(cache): apply coordinated weight limits` (`262f3914`).

**Saída verificável:** o orçamento inclui todas as imagens e metadados abrangidos, os modos funcionam e a autorização continua sendo conferida em acertos de cache.

## Tarefa 12: Revisão integrada, documentação local e entrega preparada

**Depende de:** tarefas 1–11.

**Files:**
- Modify, somente se houver regra/contrato afetado: `docs/context/openapi.yaml`, `docs/context/business-rules.md`, `docs/context/progress.md`, `docs/pending/pending-to-deploy.md`.
- Update localmente: este plano, marcando somente tarefas realmente executadas, e a spec se alguma precisão de implementação precisar ser registrada.
- Código/testes adicionais somente para regressões concretas encontradas nesta revisão, no pacote da tarefa responsável.

**Interfaces:** nenhuma API nova; relatório final distingue código verificado de operação externa pendente.

- [x] **12.1 — Revisar o diff e as cinco categorias recorrentes do projeto.** Conferir erro `ApiError`, concorrência/check-write, efeitos cruzados, limites dos campos de configuração e regras aplicadas aos formatos irmãos. Confirmar transações curtas ao redor de SQL e nenhum I/O R2 sob lock. Revisar inicialização/encerramento do cliente, transporte e scheduler. Verificar que nenhuma propriedade/exception imprime credentials, URI de banco ou payload privado.
- [x] **12.2 — Executar os testes de fronteira da entrega.** Rodar os grupos abaixo uma vez após a integração. Novas alterações/falhas justificam repetir o grupo afetado, não todos os grupos indiscriminadamente.

```powershell
./mvnw.cmd test "-Dtest=ImportTemporaryFilesTest,ImportMultipartRequestGateFilterTest,ImportMultipartStagingDirectoryTest,ImportMultipartLifecycleIntegrationTest,PrivateImportFileStoreTest,R2ImportFileStoreTest,ImportStorageConfigurationTest,ImportArtifactReaderTest"
./mvnw.cmd test "-Dtest=SafeImportReaderTest,LetterboxdExportParserTest,TraktExportParserTest,SingleFileItemHandlerTest,ImportTemplateUploadLimitTest,ImportControllerIntegrationTest,PlatformArchiveImportIntegrationTest,SingleFileImportControllerIntegrationTest,StandaloneCollectionImportIntegrationTest,ImportWorkerIntegrationTest,ImportRestartIntegrationTest,ImportPreviewServiceTest,PicksTemplateImportIntegrationTest,HistoryNormalizationTest,LetterboxdExpansionIntegrationTest,LetterboxdExpansionWorkerIntegrationTest,OpinionImportIntegrationTest,WatchEventImportIntegrationTest,ImportCollectionWritersIntegrationTest"
./mvnw.cmd test "-Dtest=ProductionDataSourcePolicyTest,ProductionConfigurationTest,DailyGameProductionConfigurationTest,CacheBudgetAllocatorTest,CacheMemoryPropertiesTest,CacheEntryWeigherTest,BudgetedCacheFactoryTest,CacheInventoryIntegrationTest,TmdbCacheConfigTest,TmdbClientCachingTest,TmdbClientDisplayDetailsTest,ContentCardAssemblerTest,DailyGameMediaServiceTest"
./mvnw.cmd compile -DskipTests
```

Os testes de import/repositório precisam de Docker. Se houver limitação de ambiente, registrar exatamente qual grupo não rodou e por quê; não marcar esse critério como aprovado. Os testes HTTP simulados não devem chamar endpoints públicos reais.

- [x] **12.3 — Conferir os caminhos que não podem sobrar.**

```powershell
rg -n 'PrivateImportFileStore|pathForReading' src/main/java/com/watchwise/watchwise_api/platformimport
rg -n 'Caffeine.newBuilder|maximumSize|maximumWeight' src/main/java
rg -n 'store.open\(context.storageKey|files.open\(context.storageKey' src/main/java/com/watchwise/watchwise_api/platformimport
rg -n 'DATABASE_URL|DB_URL|DB_USERNAME|DB_PASSWORD' src/main/resources/application-prod.properties
git diff --check
```

A primeira busca pode retornar o adaptador local e sua configuração, nunca readers/handlers. A segunda deve mostrar construção centralizada e as políticas explicitamente previstas, sem cache de resposta novo fora da conta. A terceira deve ficar vazia. A quarta não deve encontrar ligação da URI Postgres a JDBC nem o template `DB_*` antigo. Referência em comentário explicativo não conta como configuração ativa.

- [x] **12.4 — Sincronizar a documentação local.** A OpenAPI documenta 503 de infraestrutura e 500 de artefato nas operações pertinentes sem inventar rotas. `business-rules.md` aponta para os métodos implementados que mudaram ownership, quota ou limpeza. `progress.md` registra o dia real da implementação, cronologicamente e sem “próximos passos”. `pending-to-deploy.md` distingue código concluído de credenciais, deploy, upload real de 50 MiB, restauração/backup, memória e R10 ainda não validados.
- [x] **12.5 — Conferir commits e pendências.** Cada entrega tem commit de código/testes, sem docs nem autoatribuição. Nenhum push faz parte deste plano. Rodar `git status --short --branch`, `git log --format=%B ce86410d..HEAD` e `git ls-files docs` para conferir a política local. Se o HEAD mudou antes da execução, usar a base efetiva registrada na preparação para auditar apenas os commits desta implementação.
- [x] **12.6 — Entregar relatório com evidência.** Informar commits, grupos de testes executados, resultado de compilação, valores configuráveis e dependências externas restantes. Não afirmar que recursos foram provisionados ou que a API está no ar. Só depois dessa entrega o usuário precisa criar bucket/chave R2, app/Postgres Heroku e cadastrar secrets para o ensaio real.

Não criar commit apenas para os documentos desta tarefa. Um bug encontrado recebe correção e teste no componente responsável e um commit de código separado.

## Matriz de cobertura da spec

| Requisito da spec | Tarefas que o entregam | Evidência principal |
| --- | --- | --- |
| Backend R2/local, seleção única e cliente SDK | 3, 8 | Contrato local/R2 e contexto de configuração |
| Gravação limitada, hash e resposta PUT perdida | 1, 3, 7 | Servidor S3 local, reserva e compensação |
| Materialização verificada e uma leitura por sessão | 4–6 | GET contado e digest antes do parsing |
| Defesas ZIP e formatos standalone/Picks | 5–6 | Suítes de parsers e integrações de formato |
| Multipart antes do parsing e temporários privados | 1–2 | Teste real do servlet e gates de disco/concorrência |
| Limpeza, expiração, cancelamento e exclusão de usuário | 7, 12 | PostgreSQL real e falhas intermediárias |
| `ApiError`, distinção conteúdo/infraestrutura e retry | 3, 6–7 | Testes HTTP e estado do job após indisponibilidade |
| Profile prod, secrets e datasource nativo | 3, 8 | Binding, seleção e mensagens sanitizadas |
| Pool 8/2, TLS e batch inserts | 8 | Propriedades efetivas do driver, sem outro datasource |
| Worker 5000 ms e cron existente | 8 | Binding/override e configuração de jogos |
| Orçamento único incluindo imagens | 9, 11 | Soma de 21 parcelas e inventário real |
| Peso aproximado, positivo, limitado e sem overflow | 10–11 | Estruturas grandes/cíclicas e expulsão de entrada |
| Modes entries/weighted/disabled e TTL | 9–11 | Testes de retenção, loaders e compatibilidade |
| Diagnóstico interno sem dados privados | 11 | Snapshot agregado, sem novas rotas |
| Documentação local, commits e sem push automático | 12 | Git e documentação sincronizada |
| R2/Heroku reais, orçamento medido e Gate R10 | Handoff externo | Permanecem explicitamente fora da comprovação local |

## Critério de passagem entre entregas

- Armazenamento só avança para configuração final depois de todos os formatos usarem o caminho verificado e passarem os testes de falha/limpeza.
- Configuração só fica pronta depois de prod resolver suas propriedades e recusar TLS/pool inválidos sem vazamento de segredo.
- Caches só ficam prontos depois de weighted somar as 21 parcelas, disabled deixar de reter imagens/metadados e entries preservar os limites locais.
- Código preparado exige testes relevantes e compilação confirmados; produção validada depende dos recursos e medições externas descritos na spec.

## Fontes de versão e contratos externos

Reutilizar as fontes oficiais da seção 17 da spec. Para este plano, a versão de SDK foi concretizada em [AWS SDK for Java v2 2.57.0](https://github.com/aws/aws-sdk-java-v2/releases/tag/2.57.0). Os enums de checksum usados no builder existem nessa versão: [RequestChecksumCalculation](https://github.com/aws/aws-sdk-java-v2/blob/2.57.0/core/sdk-core/src/main/java/software/amazon/awssdk/core/checksums/RequestChecksumCalculation.java) e [ResponseChecksumValidation](https://github.com/aws/aws-sdk-java-v2/blob/2.57.0/core/sdk-core/src/main/java/software/amazon/awssdk/core/checksums/ResponseChecksumValidation.java).

Essas referências orientam a implementação; a compatibilidade final com o R2 continua precisando do ensaio real, depois do provisionamento.

**Execução verificada em 2026-10-28:** o primeiro grupo de imports teve 49 testes, sem falhas/erros e três skips de recursos de filesystem POSIX indisponíveis no Windows. O segundo teve 291 testes; os únicos dois erros foram os conhecidos `TransientPropertyValueException` de `UserListServiceImpl.deleteUserList` em `StandaloneCollectionImportIntegrationTest`, fora desta alteração. O grupo de configuração/cache teve 120 testes, sem falhas/erros/skips. Também passaram 39 testes de integração do controller de mídia, jogo diário e metadados de episódios. `mvnw.cmd compile -DskipTests` terminou com código 0. O review independente encontrou apenas falta de cobertura do modo disabled no cache de mídia; o teste foi adicionado e passou na matriz de 120 testes.