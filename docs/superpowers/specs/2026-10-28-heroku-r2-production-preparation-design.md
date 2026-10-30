# Preparação da API para Heroku, R2 e memória limitada

Data: 2026-10-28.
Status: spec proposta para revisão; implementação ainda não iniciada.
Base inspecionada: `main`, commit `ce86410d`.
Destino deste documento: somente `docs/` local, ignorado pelo Git. Não criar commit de documentação nem enviar este arquivo ao remoto.

## 1. Objetivo e resultado esperado

Preparar o código do Watchwise para executar em uma instância Heroku Basic, com Heroku Postgres Essential-0 e arquivos de importação em um bucket privado Cloudflare R2 Standard. A implementação deve poder ser desenvolvida e verificada localmente, antes de criar recursos externos ou fornecer credenciais reais.

Esta spec cobre os cinco pontos solicitados:

1. Implementar um adaptador R2 por trás de `ImportFileStore`, preservando o armazenamento local em desenvolvimento.
2. Adaptar os leitores de importação para trabalhar com arquivos temporários privados quando precisarem de um caminho local.
3. Separar staging temporário de armazenamento durável e completar as configurações de produção com variáveis de ambiente.
4. Configurar o pool de conexões ao Postgres e o intervalo do worker de importação.
5. Preparar os caches para terem limites por peso estimado em bytes, com o orçamento final definido após medição.

“Código preparado” significa que os adaptadores, limites, tratamento de falhas e testes locais estão concluídos. “Produção validada” exige depois os serviços reais, as credenciais e as medições no Heroku. Um estado não comprova o outro.

## 2. Decisões de infraestrutura e limites de escopo

| Aspecto | Decisão |
| --- | --- |
| Processo inicial | Uma instância Heroku Basic executando API, worker de importação e agendamentos existentes no mesmo processo |
| Build e execução | Buildpack Java oficial do Heroku, Maven e Java 21 |
| Banco | Heroku Postgres Essential-0 |
| Arquivos originais de importação | Bucket privado R2 Standard |
| Upload HTTP | Continua passando pela API e usando as rotas atuais |
| Uso de disco no Heroku | Apenas multipart, cópias de trabalho e outros arquivos temporários descartáveis |
| Desenvolvimento e testes | Adaptador local; testes do adaptador R2 usam servidor HTTP local controlado |
| Worker de importação em produção | Proposta operacional: intervalo de 5.000 ms após o término de cada execução, substituível por configuração |
| Geração dos jogos | Preservar o cron atual de dez minutos |
| Pool do banco em produção | Proposta inicial de máximo de oito conexões; mínimo ocioso de duas |
| Cache em produção | Orçamento configurável por peso estimado; valor total depende de medição |
| Múltiplas instâncias | Continuam condicionadas ao Gate R10 |

Os valores de cinco segundos e oito conexões são escolhas iniciais propostas nesta spec, não medições de capacidade. Fazem parte do desenho a revisar. O intervalo representa uma espera adicional entre execuções; não promete iniciar qualquer importação em até cinco segundos quando há fila ou processamento em andamento.

Não fazem parte desta implementação: provisionamento de contas, cobrança, DNS, escolha das URLs do frontend e da API, emissão de TLS, mudanças no fluxo de cookies/CSRF, implantação de CI/CD, backups operacionais, Sentry ou criação de novos endpoints. A política atual de privacidade e autorização dos imports deve ser preservada.

Não introduzir upload direto do navegador para o R2, URLs públicas dos arquivos, Redis, um segundo dyno de worker ou um novo mecanismo de fila. Não migrar arquivos locais existentes automaticamente: mudar o backend de armazenamento exige concluir ou expirar os jobs antigos, ou executar uma migração operacional explícita.

## 3. Estado atual verificado no código

- `ImportFileStore` já expõe `validateKey`, `store(InputStream, long, String)`, `open` e `delete`. `StoredImportFile` contém chave, SHA-256 e tamanho.
- `PrivateImportFileStore` implementa armazenamento local, valida chaves UUID, limita uploads concorrentes, calcula o digest durante a escrita e usa arquivo temporário seguido de movimentação atômica.
- `SafeImportReader` depende do tipo concreto `PrivateImportFileStore` e chama `pathForReading`. Usa duas bibliotecas ZIP para validar o arquivo, inclusive nomes, CRC e limites de expansão.
- `LetterboxdArchiveInspector` e `TraktArchiveInspector` também recebem o armazenamento local concreto. CSV/JSON e Picks-template têm consumidores que usam `ImportFileStore.open` diretamente.
- `ImportMultipartStagingDirectory` depende de `privateImportFileStore` e exige um diretório durável local para verificar a separação entre caminhos. Isso não serve para o backend R2.
- O upload reserva a quota no banco antes da gravação, executa a escrita fora dessa transação e só depois chama `markStored`. Essa separação deve continuar.
- `ImportUploadQuotaServiceImpl.markFailed` já cria tarefa durável de limpeza. `ImportCleanupJob` já usa uma reserva temporária da tarefa e faz a exclusão do arquivo fora da transação. Reutilizar esse mecanismo.
- O worker já tem intervalo configurável em `app.import.worker.fixed-delay-ms`, com fallback de 1.000 ms no código. Não é necessário inventar outro agendador.
- `application-prod.properties` ainda contém configurações essenciais comentadas e exige um diretório local durável para imports.
- `TmdbCacheConfig` cria vinte caches Caffeine com limites por quantidade de entradas. Já existe, separadamente, um cache de imagens transformadas dos jogos limitado a 50 MiB de payload em `DailyGameMediaServiceImpl`.
- O cache de imagens pesa apenas `body().length`; isso não mede chaves, objetos auxiliares ou imagens temporárias de processamento. Portanto, já existe limite de bytes em um cache, mas ainda não existe um orçamento coordenado de memória dos caches.

## 4. Necessidade, alternativas e compromissos

### 4.1 Armazenamento

A adaptação é necessária porque o arquivo original deve sobreviver ao reinício ou à substituição do processo. A implementação local não fornece essa propriedade no Heroku.

Abordagem escolhida: manter `ImportFileStore`, adicionar R2 e introduzir uma camada pequena de materialização temporária — obtenção de uma cópia local limitada e verificada para um processamento específico. Isso preserva as validações ZIP atuais e concentra a dependência do SDK no pacote de armazenamento.

Alternativas consideradas:

- Reescrever todo ZIP para leitura sequencial remota: reduz algumas cópias locais, mas altera a validação existente e o acesso necessário à estrutura do ZIP. Não se justifica nesta preparação.
- Espalhar chamadas S3 nos serviços e parsers: evita uma abstração adicional, mas duplica credenciais, falhas, limites e limpeza entre formatos. Rejeitada.

O custo da abordagem escolhida é uma cópia temporária do arquivo comprimido e, no R2, um download por sessão de leitura. Evitar novos downloads para cada entrada do ZIP ou registro do arquivo.

### 4.2 Cache

Abordagem escolhida: manter caches separados e repartir um orçamento total entre eles. Isso conserva os tipos e o comportamento dos consumidores existentes, sem construir um mecanismo próprio de expulsão global.

Um cache global único permitiria reaproveitar melhor a memória entre categorias, mas exigiria alterar chaves e consumidores de vinte caches. Apenas adicionar o mesmo limite a cada cache multiplicaria indevidamente o orçamento. Nenhuma dessas opções é a escolhida.

### 4.3 Riscos que orientam o desenho

- Arquivos privados, chaves de armazenamento e credenciais não podem aparecer nos DTOs nem nos erros públicos.
- Falha de rede não deve virar “arquivo inválido” ou consumir o job como erro determinístico.
- Uma confirmação HTTP perdida pode ocorrer depois de o R2 gravar o objeto. Limpeza deve cobrir esse caso.
- Limitar o arquivo no adaptador depois do multipart não basta para limitar os temporários criados antes pelo servidor HTTP.
- Um limite Caffeine por peso é uma estimativa do conteúdo retido, não um teto exato do heap nem da memória total do processo.
- O pool do banco é compartilhado por requisições, agendamentos e worker. Não manter conexões presas durante uploads, downloads ou parsing.

## 5. Componentes e responsabilidades

Os nomes de novos tipos abaixo são parte do desenho proposto. Seguir a organização atual em `com.watchwise.watchwise_api`.

| Componente | Responsabilidade |
| --- | --- |
| `ImportFileStore` | Contrato comum de gravação, abertura e exclusão; sem tipos do SDK na interface |
| `PrivateImportFileStore` | Backend local, selecionado apenas quando configurado |
| `R2ImportFileStore` | Backend remoto; encapsula PUT, GET e DELETE e traduz falhas do provedor |
| `ImportStorageConfiguration` / propriedades tipadas | Selecionar exatamente um backend e criar/fechar o cliente R2 |
| `ImportArtifactReference` | Chave, tamanho esperado e SHA-256 esperado, obtidos do job autorizado |
| `ImportArtifactReader` | Materializar uma referência em arquivo temporário limitado e verificar tamanho/digest antes de entregar ao parser |
| `ReadableImportArtifact` | Handle `AutoCloseable` com o caminho privado verificado; fechar libera arquivo e reservas |
| `ImportTemporaryFiles` | Diretórios privados, permissões, reservas de espaço e limpeza de temporários próprios |
| `SafeImportReader` | Validar ZIP e ler suas entradas a partir de um artefato local verificado |
| `ImportMultipartStagingDirectory` | Configurar somente o diretório de multipart e suas verificações de segurança |
| `ImportUploadAdmissionFilter` | Limitar uploads ativos antes de o multipart criar arquivos no disco |
| `CacheMemoryProperties` / `CacheBudgetAllocator` | Validar e repartir o orçamento dos caches |
| `CacheEntryWeigher` | Calcular peso estimado e limitado de uma chave e de seu valor |
| `TmdbCacheConfig` | Aplicar orçamento, estimador e expiração aos vinte caches existentes |
| `DailyGameMediaServiceImpl` | Usar sua parcela configurada e preservar as verificações de acesso às imagens |

Não criar nova entidade de negócio, nova fila ou nova tabela apenas para selecionar o backend. Os metadados de tamanho e digest já existem em `ImportJob`. Uma mudança de schema só cabe se uma necessidade demonstrada na implementação não puder ser representada pelo modelo atual; nesse caso, documentar e revisar a decisão antes de implementá-la.

## 6. Contrato do armazenamento R2

### 6.1 Cliente e isolamento

Usar AWS SDK for Java v2, módulo S3 síncrono e transporte HTTP síncrono com dependências explícitas. Fixar uma versão do BOM no Maven durante a implementação, conferida na fonte oficial; não usar `LATEST` nem intervalo aberto.

O cliente recebe endpoint HTTPS, bucket, Access Key ID e Secret Access Key por configuração. A região de assinatura é `auto`. Desativar `chunkedEncodingEnabled`, conforme a documentação R2. Não tratar o suporte a S3 como compatibilidade integral com todo recurso da AWS; configurar checksums somente de acordo com a versão fixada e a matriz oficial.

O bucket permanece privado. O token deve ter acesso somente aos objetos do bucket necessário. Usar bucket separado para testes reais e para produção. O backend não precisa de URL pública, CORS do bucket nem permissão para administrar buckets.

A chave persistida continua sendo o UUID validado. No R2, armazenar sob o prefixo fixo `imports/`, resultando em `imports/{uuid}`. Não usar nome original, e-mail, ID fornecido pelo usuário ou caminho de diretório na chave. Mudar bucket ou prefixo com jobs pendentes é uma migração operacional, não uma configuração intercambiável sem consequência.

### 6.2 Gravação

1. Validar a chave e o limite efetivo antes de qualquer acesso remoto.
2. Obter o slot de upload e a reserva de espaço temporário. Não manter transação SQL aberta.
3. Copiar a entrada para um temporário privado com buffer pequeno fixo, contando bytes reais e calculando SHA-256. Rejeitar no primeiro byte que exceder o limite; não confiar apenas no tamanho declarado.
4. Enviar com `RequestBody.fromFile` ou corpo reabrível equivalente de tamanho conhecido. Não usar `readAllBytes`, `ByteArrayOutputStream` do arquivo inteiro nem o caminho síncrono do SDK que acumula um stream de tamanho desconhecido na memória.
5. Usar PUT condicional `If-None-Match: *` para impedir substituição de uma chave existente. Incluir metadados próprios de digest, tamanho e identificador da operação de upload, estável durante as tentativas dessa operação.
6. Uma repetição após resposta perdida só pode reconhecer sucesso anterior quando os metadados da própria operação coincidirem. Objeto preexistente de outra operação é colisão interna; não sobrescrever, adotar ou apagar seu conteúdo.
7. Retornar `StoredImportFile` somente quando a gravação tiver confirmação ou reconciliação segura. O ETag do provedor não substitui o SHA-256 calculado pela aplicação.
8. Em qualquer saída, remover o temporário e liberar reservas. Falha de limpeza local deve ser registrada e elegível à varredura posterior.

O tratamento da reserva de upload precisa distinguir a colisão comprovada de uma gravação com resultado desconhecido: na colisão, falhar e liberar a reserva sem agendar exclusão do objeto preexistente. Manter `upload_size_bytes = 0` e digest ausente nessa reserva impede que as rotinas posteriores a tratem como um arquivo pertencente ao job. Testar os caminhos de limpeza que usam essa reserva.

### 6.3 Leitura e exclusão

- `open` retorna stream de resposta; o fechamento deve encerrar a resposta HTTP ou abortá-la quando necessário, devolvendo os recursos do cliente.
- O consumidor não recebe URLs do R2, respostas do SDK ou acesso a objetos de outros usuários. A autorização do job ocorre antes de montar a referência de leitura.
- `delete` é idempotente: objeto ausente equivale a exclusão concluída. Falhas remotas não equivalem a sucesso.
- Não fazer uma chamada HEAD antes de cada DELETE. Na leitura, HEAD adicional só é justificável pela reconciliação de uma escrita incerta ou por uma verificação que não possa usar os dados do GET.
- Não listar o bucket para processar um import. Uma listagem remota de todos os objetos não pertence ao caminho de requisição.

### 6.4 Prazos e tentativas

Configuração inicial proposta: conexão de 3.000 ms, timeout de leitura inativa de 5.000 ms, até 10.000 ms por tentativa e até 20.000 ms para a operação inteira, com no máximo duas tentativas no total. Os prazos totais precisam incluir o consumo do corpo GET, não apenas o recebimento dos headers.

Validar valores positivos e a relação tentativa <= operação. Repetir apenas falhas transitórias; falha de credencial não deve provocar uma sequência rápida de novas tentativas. O job pode permanecer elegível a retry com backoff enquanto sua política de expiração permitir correção da configuração.

Esses valores são limites iniciais de espera, sujeitos ao ensaio de upload de 50 MiB. O timeout HTTP do Heroku exige que o trecho síncrono após o recebimento da requisição tenha margem para terminar antes de 30 segundos. Não afirmar suporte operacional a 50 MiB sem essa evidência. Upload direto ou alteração do contrato para upload assíncrono exigiriam outra decisão de escopo.

## 7. Materialização e segurança dos arquivos temporários

### 7.1 Sessão de leitura

`ImportArtifactReference` deve ser construído com a chave, `uploadSizeBytes` e `zipDigest` do job já autorizado ou reivindicado pelo worker. O nome histórico `zipDigest` também representa o digest dos arquivos standalone; não renomear a coluna nesta mudança.

`ImportArtifactReader.open(reference)` retorna um `ReadableImportArtifact` depois de baixar/copiar no máximo o tamanho permitido, conferir o tamanho esperado e verificar SHA-256. O parser só recebe o arquivo depois dessa verificação completa. Fechar o handle apaga a cópia temporária e libera a reserva, inclusive quando o parser lança exceção.

Usar a mesma camada para ZIP, CSV, JSON e Picks-template. Atualizar também leitores que fazem inspeção inicial do formato: uma falha de armazenamento não pode ser engolida como “não é este formato”. Não criar um acesso alternativo ao R2 por formato.

Uma operação de inspeção/parsing abre uma sessão e compartilha o mesmo arquivo nas suas etapas internas. Não baixar por entrada do ZIP, item de importação ou tentativa de identificar o formato. Operações posteriores podem abrir nova sessão; não criar cache persistente de arquivos em `/tmp` nesta etapa.

Os consumidores deixam de depender de `PrivateImportFileStore` e de `pathForReading`. A interface comum não ganha um método que prometa caminho local durável para R2. O SDK permanece encapsulado no armazenamento.

### 7.2 Diretórios e limites

Em produção, usar uma raiz privada dedicada sob `java.io.tmpdir`, com áreas separadas para multipart, preparação de uploads e artefatos de leitura. No backend local, essa raiz não pode se sobrepor ao diretório durável nem alcançá-lo por symlink.

- Diretórios com permissão `0700` e arquivos `0600` quando POSIX estiver disponível.
- Recusar raiz do filesystem, o próprio diretório temporário global, symlinks e caminhos normalizados que escapem da raiz dedicada.
- Usar nomes gerados pelo backend e criação exclusiva. Nunca extrair entradas ZIP para caminhos fornecidos pelo arquivo.
- Preservar proteção contra ZIP bomb, travessia de diretório, nomes duplicados, ZIP aninhado, divergência entre leitores, CRC incorreto e limites de expansão, registros e campos.
- O limite atual é 50 MiB para arquivo original, 5 MiB para Picks-template, 512 MiB de conteúdo expandido e 64 MiB por entrada. Conteúdo expandido deve continuar sendo consumido em fluxo; não materializar os 512 MiB integralmente.

Manter os dois uploads simultâneos atuais e propor dois slots simultâneos de materialização por processo. Ambos são configuráveis. A reserva de temporários deve considerar o pior caso de coexistência de multipart, cópia de upload e cópia de leitura; não confundir esse espaço local com a quota global de arquivos pendentes no R2.

Derivar o orçamento mínimo reservado de `U × (M + F) + R × F`, em que `U` é a concorrência de uploads, `R` a concorrência de leituras, `M` o máximo da requisição multipart e `F` o máximo do arquivo original. Com os limites atuais e dois slots de cada tipo, são 302 MiB de disco temporário reservado. Isso é uma conta de espaço em disco, não um orçamento de RAM. Verificar também espaço livre real antes da cópia; reserva em memória não garante disponibilidade do filesystem.

O controle de admissão HTTP precisa ocorrer antes da resolução multipart. Reutilizar o limite existente de uploads, sem limitar somente o `store` depois de o servidor ter criado o temporário. Aplicar à rota de upload real, respeitando a ordem de autenticação, CSRF e os limites existentes. Liberar a reserva após a limpeza multipart, inclusive em erro ou desconexão.

Requisição de upload sem slot retorna o erro de limite já padronizado, com HTTP 429. Saturação temporária de materialização durante leitura é falha transitória HTTP 503 ou retry do worker. Não bloquear um número ilimitado de threads esperando espaço.

### 7.3 Limpeza local

Apagar os arquivos da sessão no fechamento. Para arquivos deixados por interrupção abrupta, usar diretórios de execução identificáveis e um lock de processo: a varredura só pode remover temporários próprios de execuções comprovadamente inativas. Não apagar um arquivo ativo apenas porque passou de certa idade nem executar uma exclusão genérica sobre todo `/tmp`.

Se um arquivo não puder ser removido, contabilizar o espaço até a limpeza posterior; não liberar ficticiamente bytes e permitir uso local sem limite. Ao reiniciar, reconstruir a contabilização dos temporários remanescentes que pertencem à aplicação.

## 8. Consistência com banco, retries e limpeza durável

Preservar a sequência atual: reservar quota no banco -> gravar o arquivo fora da transação -> registrar digest/tamanho e disponibilizar trabalho em transação curta. O worker não deve ler uma reserva cujo upload ainda não foi confirmado.

O banco e o R2 não compartilham uma transação. Reutilizar a compensação existente em vez de fingir atomicidade entre os dois:

- PUT confirmado e falha em `markStored`: marcar falha quando possível e manter tarefa de exclusão durável.
- PUT com resultado desconhecido: manter o mecanismo de limpeza mesmo quando `storedFile` não foi retornado. `markFailed` já agenda limpeza; preservar essa cobertura.
- Processo encerrado antes de marcar falha ou confirmar a gravação: a reserva expira e a limpeza existente recupera o caso.
- Exclusão falha ou R2 indisponível: persistir o retry da limpeza. Quota só deve ser liberada conforme a confirmação de exclusão e as regras atuais; uma exceção não pode fingir que o objeto desapareceu.
- Retry, cancelamento, expiração, conclusão e exclusão de usuário devem funcionar com o mesmo backend. Não restaurar um job cujo arquivo já foi removido.
- Preservar a seleção de tarefas, a reserva temporária e o backoff já existentes em `ImportCleanupJob`. Confirmar que o timeout remoto é menor que a reserva da tarefa e que uma confirmação atrasada não finaliza trabalho pertencente a uma reivindicação mais nova.

Não fazer I/O remoto enquanto houver lock de quota, lock pessimista do job ou transação de gravação aberta. Revisar também os fluxos de retry e preview, não apenas o upload.

Não configurar uma regra R2 de expiração automática arbitrária que possa apagar arquivos de jobs ainda válidos. A limpeza de objetos segue a situação persistida dos jobs. Lifecycle de bucket só poderá ser usado como recuperação adicional depois de compatibilizado com essa retenção.

## 9. Falhas e contrato HTTP

Introduzir exceções internas de armazenamento distinguindo falha transitória, arquivo persistido ausente/corrompido e colisão. Reaproveitar `ServiceUnavailableException` e o tratamento `ApiError` quando compatível; nenhuma exceção do SDK pode escapar para o corpo público.

| Situação | HTTP quando ocorre em requisição | Comportamento do worker |
| --- | --- | --- |
| Conteúdo ou ZIP inválido | 400, conforme o contrato existente | Falha determinística |
| Limite HTTP de multipart excedido | 413, conforme o tratamento existente | Não se aplica |
| Quota ou concorrência de upload excedida | 429 | Não criar trabalho além do permitido |
| R2 sem resposta, indisponível ou limitando chamadas | 503 com mensagem genérica | Retry com backoff, preservando progresso e idempotência |
| Credenciais R2 recusadas | 503 genérico e registro operacional sanitizado | Backoff limitado pela política do job; sem repetição rápida |
| Arquivo de job confirmado ausente ou digest divergente | 500 genérico, sem chave/digest no corpo | Interromper como falha de artefato; não aplicar dados de arquivo parcial |
| Chave remota colidente de outra operação | 500 genérico | Falhar a reserva sem alterar o objeto preexistente |
| DELETE de objeto ausente | Sucesso da limpeza | Concluir a tarefa de forma idempotente |

Revisar os `catch` de `SafeImportReader`, `SingleListImportHandler`, `SingleWatchlistImportHandler`, `PicksTemplateImportHandler`, inspeção de formato, preview e retry de job. Hoje alguns convertem `IOException` em arquivo inválido ou retornam `false`. O erro remoto deve preservar sua classificação através dessas camadas.

Adicionar tratamento específico da indisponibilidade de storage no worker antes do `catch RuntimeException`: a classificação não pode depender de o job estar em `RESOLVE` ou em outra fase. Falhas transitórias em leitura não devem consumir o progresso já confirmado como falha determinística.

## 10. Configuração de produção

Usar propriedades tipadas e validação de inicialização. Ausência ou erro de configuração deve produzir uma mensagem que nomeia a propriedade, sem imprimir o valor secreto. Validar o backend selecionado sem consultar R2 durante a construção de todos os beans.

### 10.1 Storage e temporários

| Propriedade | Variável de ambiente proposta | Política |
| --- | --- | --- |
| `app.import.storage.backend` | `WATCHWISE_IMPORT_STORAGE_BACKEND` | `local` em dev/test; `r2` por padrão em prod; valor desconhecido falha |
| `app.import.storage-directory` | `WATCHWISE_IMPORT_STORAGE_DIRECTORY` | Exigida somente no backend local |
| `app.import.r2.endpoint` | `WATCHWISE_R2_ENDPOINT` | HTTPS obrigatório em produção; credenciais embutidas, query e fragment não são aceitos |
| `app.import.r2.bucket` | `WATCHWISE_R2_BUCKET` | Obrigatória em R2 |
| `app.import.r2.access-key-id` | `WATCHWISE_R2_ACCESS_KEY_ID` | Obrigatória e secreta em R2 |
| `app.import.r2.secret-access-key` | `WATCHWISE_R2_SECRET_ACCESS_KEY` | Obrigatória e secreta em R2 |
| `app.import.r2.region` | Sem variável obrigatória | `auto` |
| `app.import.staging-directory` | `WATCHWISE_IMPORT_STAGING_DIRECTORY` | Base privada opcional para a raiz temporária da execução |
| `app.import.temporary-directory` | `WATCHWISE_IMPORT_TEMPORARY_DIRECTORY` | Alias alternativo da mesma raiz |
| `app.import.max-concurrent-uploads-per-process` | `WATCHWISE_IMPORT_MAX_CONCURRENT_UPLOADS` | Dois; positivo |
| `app.import.max-concurrent-artifact-reads-per-process` | `WATCHWISE_IMPORT_MAX_CONCURRENT_ARTIFACT_READS` | Dois; positivo |

O manager escolhe `staging-directory`, depois `temporary-directory`, e usa `${java.io.tmpdir}/watchwise-import-work` quando ambos estão vazios. Se ambos estiverem definidos, os caminhos normalizados precisam ser iguais. A raiz única coordena a quota de staging, cópia de upload e leitura; dentro dela, cada execução recebe pastas privadas próprias. `spring.servlet.multipart.location` não é o caminho efetivo: o bean multipart recebe `run-UUID/multipart` do manager.

Os timeouts definidos na seção 6 também devem ser configuráveis sob `app.import.r2`, em milissegundos, com valores iniciais explícitos no profile e validação das relações. Testes podem injetar endpoint HTTP local sem relaxar a regra HTTPS de produção.

Registrar `PrivateImportFileStore` apenas para backend local e `R2ImportFileStore` apenas para R2. Não manter dois candidatos nem usar fallback silencioso para disco quando faltar credencial do R2. Remover a dependência `@DependsOn("privateImportFileStore")` da configuração de multipart.

### 10.2 Profile e propriedades essenciais

Completar `application-prod.properties`, preservando os valores não secretos já adotados no projeto:

- Ativação explícita com `SPRING_PROFILES_ACTIVE=prod` e `server.port=${PORT:8080}`.
- Docker Compose de desenvolvimento desativado no profile de produção.
- Flyway habilitado, `ddl-auto=validate`, `open-in-view=false` e logs SQL desligados.
- JWT por variável de ambiente, cookies seguros, origens CORS exatas e TMDB configurados.
- Reaproveitar as variáveis existentes `JWT_SECRET`, `CORS_ALLOWED_ORIGINS`, `APP_TMDB_API_KEY`, `RESEND_API_KEY`, `RESEND_FROM_EMAIL` e `RESEND_EMAIL_ENABLED`.
- O bean Google atual exige `app.oauth.google.client-id`. Manter `GOOGLE_OAUTH_CLIENT_ID` como configuração obrigatória enquanto esse bean for incondicional; esta spec não desativa nem redesenha OAuth.
- Ativar configurações exigidas pelos beans de rate limit e cache que hoje estão comentadas, usando os padrões atuais de desenvolvimento como base explícita revisada. Nenhum placeholder obrigatório deve ficar sem origem no profile prod.
- Preservar `/api/v1`. A integração final de proxy, domínio e CSRF continua na etapa de configuração do deploy.

Sem credenciais reais é possível desenvolver e testar as validações. Não prometer inicialização funcional em produção com secrets ausentes.

## 11. PostgreSQL e orçamento de conexões

O Heroku fornece `DATABASE_URL` em formato de URI Postgres. Não ligá-la diretamente a `spring.datasource.url`, que espera JDBC. O buildpack Java oficial já prepara as variáveis `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` e `SPRING_DATASOURCE_PASSWORD` a partir das informações de conexão disponíveis.

Usar esse binding nativo do Spring Boot como caminho de produção. Não criar conversor próprio de `DATABASE_URL` nem substituir a configuração automática do datasource: isso duplicaria trabalho do buildpack e acrescentaria tratamento desnecessário de credenciais e encoding. Para executar o profile prod em teste local, fornecer as mesmas propriedades `spring.datasource.*` com um banco local controlado.

Os placeholders `DB_URL`/`DB_USERNAME`/`DB_PASSWORD` estão apenas no template comentado de produção atual; substituí-los pela configuração nativa documentada, sem adicionar outra hierarquia de variáveis concorrentes. Em ambientes que não usam o buildpack, o responsável fornece diretamente `SPRING_DATASOURCE_*` em formato JDBC. Credenciais presentes na URL são secretas; não registrar a URL completa nem decodificar e reconstruir senhas sem necessidade.

Configurar `reWriteBatchedInserts=true` nas propriedades do driver. Exigir TLS efetivo com `sslmode=require` no mínimo, conforme o caminho documentado pelo Heroku, e rejeitar valores conflitantes que permitam conexão sem criptografia. O pgJDBC aceita qualquer certificado de servidor nesse modo: não descrevê-lo como verificação de identidade. Permitir `verify-ca` ou `verify-full` quando a cadeia de confiança necessária estiver configurada. Os testes devem verificar as propriedades finais entregues ao driver, inclusive precedência de parâmetros da URL, em vez de apenas conferir texto no arquivo de configuração.

Configuração inicial de Hikari:

| Propriedade | Variável | Padrão proposto |
| --- | --- | --- |
| `spring.datasource.hikari.maximum-pool-size` | `DB_POOL_MAX_SIZE` | 8 |
| `spring.datasource.hikari.minimum-idle` | `DB_POOL_MIN_IDLE` | 2 |
| `spring.datasource.hikari.connection-timeout` | `DB_POOL_CONNECTION_TIMEOUT_MS` | 5.000 ms |

Validar mínimo >= 0, máximo > 0 e mínimo <= máximo. O Essential-0 oferece vinte conexões; oito por processo deixam margem para operação e sobreposição transitória de processos durante deploy. O orçamento deve contar também conexões administrativas, migrations e comandos avulsos. Não tratar oito como prova de que o banco ou a API suportam cem usuários simultâneos.

A API, os jobs e o worker usam o mesmo datasource. Não abrir um segundo pool para R2, worker ou Flyway por conveniência. Qualquer nova instância ou tarefa operacional deve ser contabilizada antes de aumentar o total de processos.

## 12. Worker e agendamentos

Definir no profile prod `app.import.worker.fixed-delay-ms=${IMPORT_WORKER_FIXED_DELAY_MS:5000}`. Preservar a configuração atual de dev e a possibilidade de desativar worker/limpeza nos testes.

Validar intervalo positivo. `fixedDelay` mede a espera após a execução anterior terminar; uma execução demorada prolonga o tempo até a consulta seguinte. Sem trabalho, o worker continua consultando periodicamente o banco. Esta alteração reduz essa frequência; não transforma o sistema em fila por notificações.

Preservar tamanho de lote, leases, heartbeat, regras de idempotência e cron de jogos. Não adicionar uma nova thread ou uma segunda agenda para fazer a mesma reivindicação. O valor do intervalo não altera a necessidade do Gate R10 antes de múltiplas instâncias.

## 13. Caches por peso estimado

### 13.1 Modos de operação e orçamento

Definir `app.cache.memory.mode` com três valores:

- `entries`: compatibilidade local, mantendo os limites atuais por quantidade de entradas nos caches TMDB e o limite de imagens existente; padrão em dev/test.
- `weighted`: orçamento coordenado por peso estimado; padrão de prod e requer valor total explícito.
- `disabled`: sem retenção nos caches abrangidos; permitido para medição controlada do consumo base antes de escolher o orçamento.

Em `weighted`, exigir `WATCHWISE_CACHE_MAX_ESTIMATED_BYTES` para `app.cache.memory.max-estimated-bytes`. Não preencher com um valor apresentado como medido ou adequado ao dyno antes de medir. Em `disabled`, o total não é exigido e todos os caches abrangidos devem deixar de reter entradas, inclusive imagens transformadas; os loaders continuam funcionais.

Manter `WATCHWISE_CACHE_MEDIA_MAX_ESTIMATED_BYTES`, padrão de 52.428.800 bytes, como parcela proposta para imagens — continuidade do teto atual de 50 MiB, e não uma nova medição. Validar `0 < media < total` no modo weighted.

O restante `total - media` é dividido entre os vinte caches TMDB. Padrão: vinte parcelas iguais, arredondadas para baixo, sem redistribuir o resto. Aceitar um mapa opcional de participações inteiras positivas por nome de bean; nomes desconhecidos falham, caches omitidos usam participação um. Calcular a soma usando todos os caches reais e aritmética protegida contra overflow. Nenhuma parcela pode ser zero. A soma das parcelas e da parcela de imagens nunca ultrapassa o total configurado.

O cache de imagens deve entrar nessa conta; dar todo o orçamento aos metadados e manter mais 50 MiB por fora violaria a política. Coleções temporárias de uma requisição, mapas de locks e tarefas em andamento não são caches de resposta abrangidos por esse orçamento: devem entrar na medição do processo e continuar com seus controles próprios.

### 13.2 Aplicação no Caffeine

Em `weighted`, usar `maximumWeight` com um `weigher`. Não combinar com `maximumSize` no mesmo builder; os limites de entradas atuais passam a atender apenas o modo `entries`. Preservar os tempos de expiração atuais de cada cache.

Dar peso positivo a toda entrada, inclusive resultados `NotFound`. Continuar sem reter indisponibilidade do TMDB quando o comportamento atual a evita. Não permitir que uma quantidade ilimitada de resultados vazios fique com peso zero.

O estimador deve considerar chave, wrapper, valor e estruturas internas. Preferir contagem estrutural em objetos de dados conhecidos: records, strings, enums, tipos escalares, listas, mapas, Optional e arrays de bytes. Evitar serializar o payload inteiro em um segundo `byte[]`, usar instrumentação de JVM ou percorrer entidades JPA.

Usar uma estimativa documentada e testável de overhead por tipo e espaço de caracteres/referências, com aritmética saturada. A visita deve ter limite de profundidade e de nós, detecção de ciclos e custo limitado. Tipo não suportado ou limite de visita excedido deve produzir peso de rejeição da retenção, não peso um e não falha da requisição. Adicionar um teste de cobertura para os tipos de valores usados pelos vinte caches para que esse fallback não esconda caches permanentemente ineficazes.

O peso individual do Caffeine é `int`; impedir overflow e não converter uma entrada maior que seu orçamento em um peso pequeno. Os orçamentos por cache devem ser menores que `Integer.MAX_VALUE`; o estimador pode usar um peso acima do orçamento desse cache para impedir sua retenção.

Esse cálculo continua sendo uma aproximação do heap retido. Não afirmar que `maximumWeight` garante RSS ou heap exatos: manutenção do cache, requisições, JVM, arrays temporários e processamento de imagens consomem memória adicional, e a expulsão pode ter manutenção posterior à inserção.

Os objetos armazenados devem ser tratados como imutáveis. Como o peso é calculado na inserção/substituição, mutação posterior de coleções ou arrays pode invalidar a estimativa. Auditar os consumidores, preservar clones defensivos das imagens e substituir entradas quando houver atualização. Não tentar recalcular peso lendo continuamente todo cache.

### 13.3 Observação e medição posterior

Habilitar estatísticas dos caches e obter, por meios internos, nome, modo, orçamento, peso estimado retido, quantidade de entradas, acertos, falhas e expulsões. A observação não pode conter chaves de consulta ou payloads pessoais nem adicionar endpoint público. Usar a política Caffeine e os mecanismos internos de diagnóstico já disponíveis; uma plataforma de monitoramento fica fora do escopo.

No Heroku, medir primeiro consumo base e picos com retenção desativada em acesso controlado; depois definir o total weighted e repetir o cenário com navegação, jogos e imports. Considerar heap, memória nativa, buffers, threads e transformação simultânea de imagens. Só então registrar o valor adotado e a evidência operacional na documentação local.

## 14. Verificação da implementação futura

Esta seção define os testes necessários quando o código for implementado. A criação desta spec não executa nem comprova esses testes.

### 14.1 Contrato dos adaptadores

Executar a mesma suíte de contrato para local e R2, este contra servidor HTTP local controlado. Cobrir gravação/leitura/exclusão, digest e contagem reais, limite excedido, chave inválida, exclusão repetida, fechamento de stream e prevenção de sobrescrita. Nenhum teste local depende de conta AWS/Cloudflare ou chave real.

No adaptador R2, verificar também resposta perdida após PUT, retry com corpo reabrível, colisão de outra operação, GET interrompido, respostas 403/404/429/5xx, timeout, compatibilidade dos headers e liberação dos recursos. A simulação local verifica comportamento do cliente; não comprova a compatibilidade com o R2 real.

### 14.2 Temporários e imports

- Preservar a suíte `SafeImportReaderTest` e suas proteções ZIP; acrescentar leitura por artefato materializado.
- Verificar fechamento em sucesso, parser inválido, corrupção, leitura interrompida e consumidor que abandona a leitura.
- Exercitar ZIP Letterboxd/Trakt, CSV/JSON standalone e Picks-template usando o mesmo caminho de artefato.
- Verificar uma obtenção remota por sessão, em vez de uma por entrada, e a liberação de reservas.
- Verificar controle de concorrência antes do multipart, inclusive limite, autenticação/CSRF, desconexão e limpeza posterior.
- Verificar permissões, symlinks, caminhos sobrepostos, espaço insuficiente, temporário abandonado, execução ainda ativa e falha ao excluir um arquivo.
- Testar com PostgreSQL real os fluxos de reserva, `markStored`, falha, retry, expiração e limpeza. Cobrir objeto gravado antes de uma falha SQL e indisponibilidade temporária do R2 sem duplicar fatos do usuário.
- Verificar a classificação HTTP com `ApiError` e a classificação de retry do worker em todas as fases afetadas.

### 14.3 Configuração, pool e caches

- Seleção única local/R2; configurações obrigatórias apenas do backend selecionado; falta de secret sem vazamento em erro.
- Profile prod isolado de dev, porta Heroku, JPA/Flyway, cookies seguros e propriedades exigidas pelos beans existentes.
- Binding das variáveis nativas do buildpack para o datasource, credenciais preservadas sem reconstrução, TLS efetivo e precedência de parâmetros da URL; limites positivos do pool.
- Configuração de 5.000 ms em prod e override, sem redefinir a agenda de jogos.
- Repartição de orçamento, nomes de cache, overflow, parcelas inválidas e soma incluindo os 50 MiB configuráveis de imagens.
- Peso positivo de entradas pequenas/negativas, peso crescente para payloads maiores, arrays, tipos usados pelos caches e ausência de retenção de entrada maior que a parcela.
- Estimador limitado diante de ciclos/estruturas excessivas, sem derrubar a requisição.
- Expiração existente, comportamento de `NotFound`/`Unavailable`, modo sem retenção e autorização preservada mesmo em acerto do cache de imagens.

Rodar testes focados nos componentes alterados e a compilação Java 21. Testes de repositório exigem Docker. Ampliar a execução somente se mudanças, falhas ou dependências afetadas justificarem.

## 15. Arquivos afetados e sequência recomendada

| Entrega | Arquivos existentes principais | Novos arquivos propostos |
| --- | --- | --- |
| Contrato R2 e seleção de backend | `pom.xml`, `platformimport/storage/ImportFileStore.java`, `PrivateImportFileStore.java` | Adaptador, configuração/propriedades e exceções de storage |
| Temporários e leitura | `platformimport/config/ImportMultipartStagingDirectory.java`, `platformimport/parser/SafeImportReader.java`, `platformimport/model/ImportReadContext.java` quando necessário | `ImportArtifactReference`, `ImportArtifactReader`, `ReadableImportArtifact`, `ImportTemporaryFiles`, controle de admissão |
| Integração dos formatos e falhas | `LetterboxdArchiveInspector`, `TraktArchiveInspector`, handlers standalone/Picks, serviços de job/preview/quota, `ImportWorker`, `ImportCleanupJob`, `GlobalExceptionHandler` | Testes dos fluxos alterados |
| Produção e conexões | `application.properties`, `application-dev.properties`, `application-prod.properties`, binding nativo do datasource | Validação de configuração efetiva quando o binding nativo não cobrir a restrição exigida |
| Memória dos caches | `common/tmdb/TmdbCacheConfig.java`, `dailygame/service/impl/DailyGameMediaServiceImpl.java` e consumidores que precisem de proteção contra mutação | Propriedades de memória, alocador, estimador e testes |

Ordem: armazenamento e seleção do backend; temporários e leitores; propagação de falhas/limpeza; configuração prod e pool; worker; orçamento dos caches; verificações de regressão e revisão conjunta.

Ao implementar, sincronizar `docs/context/business-rules.md` se regras relevantes mudarem e registrar somente mudanças efetivamente concluídas em `docs/context/progress.md`, na data de implementação. Atualizar a OpenAPI local caso o contrato de erro de alguma operação precise refletir a classificação de indisponibilidade. Não acrescentar endpoints ou alterar schema público como efeito colateral. A documentação continua local e fora dos commits.

Esta spec não é um plano de implementação com passos de código; esse plano deve ser escrito a partir do desenho revisado, antes das alterações de produção.

## 16. Critérios de conclusão e dependências externas

### Código concluído antes de configurar serviços

- Existe um único `ImportFileStore` ativo, local em desenvolvimento e R2 em produção.
- Nenhum leitor em produção exige caminho durável local nem faz acesso direto ao SDK.
- Uploads, downloads e temporários têm limites reais e encerram seus recursos nas saídas de sucesso e falha.
- Digest/tamanho são verificados antes do parsing; as proteções dos arquivos originais permanecem.
- Quotas, autorização, retries e limpeza durável funcionam em ambos os backends, inclusive falhas intermediárias.
- Produção tem configuração ativa e validada para receber as variáveis, pool explícito e intervalo do worker substituível.
- Os vinte caches TMDB e o cache de imagens participam da política de memória; o orçamento total não é multiplicado pela quantidade de caches.
- Testes locais relevantes e compilação passaram, com evidências registradas no relatório da implementação.
- Nenhum secret real foi exigido, escrito no repositório ou incluído em logs/documentação.

### Depois, com serviços provisionados

O usuário cria o bucket privado e a credencial limitada do R2, provisiona app/banco no Heroku e cadastra as variáveis no painel. Não precisa fazer isso para escrever o código desta spec.

Com esses recursos, validar uma gravação/leitura/exclusão real, arquivo ainda acessível após reinício da API, continuidade de um job/retry e limpeza. Validar upload de 50 MiB dentro do comportamento HTTP do Heroku e medir memória/latência com imports e jogos. Definir então o orçamento total dos caches e verificar o pool sob carga.

Registrar separadamente qualquer pendência de credencial, compatibilidade real, latência, espaço temporário ou memória. Não declarar Gate R10, capacidade para cem usuários simultâneos ou deploy completo a partir dos testes locais.

## 17. Fontes oficiais consultadas

Referências verificadas em 2026-10-28. Os tamanhos de pool, timeouts e controles de temporários propostos acima são decisões desta spec; não são valores recomendados universalmente pelos fornecedores.

- [Cloudflare R2 com AWS SDK for Java v2](https://developers.cloudflare.com/r2/examples/aws/aws-sdk-java/): configuração do cliente e necessidade de desativar chunked encoding.
- [Compatibilidade S3 do R2](https://developers.cloudflare.com/r2/api/s3/api/): endpoint, região `auto`, operações disponíveis, condições de PUT e limites de checksums.
- [AWS SDK Java: uploads de streams](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/best-practices-s3-uploads.html): tamanho conhecido, corpo reabrível e risco de acumular stream de tamanho desconhecido na memória.
- [Caffeine: eviction](https://github.com/ben-manes/caffeine/wiki/Eviction): peso calculado na criação/substituição e política de expulsão.
- [Caffeine: contrato do builder](https://github.com/ben-manes/caffeine/blob/master/caffeine/src/main/java/com/github/benmanes/caffeine/cache/Caffeine.java): exclusividade entre `maximumSize` e `maximumWeight`.
- [HikariCP: configuração](https://github.com/brettwooldridge/HikariCP#configuration-knobs-baby): máximo de conexões, mínimo ocioso e espera por conexão.
- [Heroku Postgres: planos](https://devcenter.heroku.com/articles/heroku-postgres-plans): limite de vinte conexões no Essential-0.
- [Heroku Postgres: conexão](https://devcenter.heroku.com/articles/connecting-heroku-postgres): configuração de acesso à instância gerenciada.
- [Heroku: bancos relacionais com Java](https://devcenter.heroku.com/articles/connecting-to-relational-databases-on-heroku-with-java): variáveis JDBC e `SPRING_DATASOURCE_*` fornecidas pelo buildpack.
- [pgJDBC: SSL](https://jdbc.postgresql.org/documentation/ssl/): diferença entre criptografia com `require` e validação de certificado/hostname.
- [Heroku: request timeout](https://devcenter.heroku.com/articles/request-timeout): limite do router e implicações para upload/processamento síncrono.
