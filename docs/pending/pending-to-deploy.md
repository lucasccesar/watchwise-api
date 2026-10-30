# Pendências para o primeiro deploy

Revisado em 2026-10-28 contra o código atual. As decisões do piloto são Heroku Basic com Heroku Postgres Essential-0, uma réplica inicial e Cloudflare R2 privado para os imports. O código está preparado para essa arquitetura e o buildpack Java está fixado em JDK 21 (`system.properties`); os serviços externos ainda não foram provisionados nem testados com credenciais reais.

## 1. Provisionar os serviços escolhidos

- Criar o app Heroku, anexar o Postgres Essential-0 e confirmar a região.
- Criar um bucket privado R2 Standard. Gerar credenciais limitadas ao bucket e guardar endpoint, bucket, access key e secret key.
- Confirmar os domínios finais da API e do frontend antes de cadastrar CORS, OAuth e DNS.
- Manter uma réplica até concluir os testes operacionais e o Gate R10.

## 2. Cadastrar a configuração de produção

O profile `prod` usa as variáveis padrão do Spring para o datasource. Configure `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` e `SPRING_DATASOURCE_PASSWORD` com os dados do Postgres em formato JDBC. A API não converte `DATABASE_URL` automaticamente. O profile exige TLS e `reWriteBatchedInserts`; o pool começa com máximo 8, mínimo ocioso 2 e timeout de 5 segundos.

Cadastrar também:

- `SPRING_PROFILES_ACTIVE=prod`, `JWT_SECRET`, `CORS_ALLOWED_ORIGINS` e `APP_TMDB_API_KEY`.
- `RESEND_EMAIL_ENABLED=true`, `RESEND_API_KEY` e `RESEND_FROM_EMAIL` usando um remetente autorizado no domínio verificado.
- `WATCHWISE_IMPORT_STORAGE_BACKEND=r2`, `WATCHWISE_R2_ENDPOINT`, `WATCHWISE_R2_BUCKET`, `WATCHWISE_R2_ACCESS_KEY_ID` e `WATCHWISE_R2_SECRET_ACCESS_KEY`.
- `GOOGLE_OAUTH_CLIENT_ID` se o login Google for usado.
- `WATCHWISE_CACHE_MAX_ESTIMATED_BYTES` antes de usar o modo ponderado padrão em produção. A aplicação falha cedo se o valor não for configurado ou não permitir parcelas positivas para os 20 caches TMDB e o cache de mídia. `WATCHWISE_CACHE_MEDIA_MAX_ESTIMATED_BYTES` começa em 52.428.800 bytes, preservando o teto legado de 50 MiB das imagens.

O teto total de cache ainda não foi escolhido porque depende de medir o consumo real no dyno de 512 MiB. Para um ambiente de medição, `WATCHWISE_CACHE_MODE=disabled` inicia sem retenção de cache e mantém os loaders funcionais. Depois da medição, configurar o total e voltar a `weighted`; o modo sem cache não é a configuração final recomendada para o uso regular.

## 3. Publicar e validar o funcionamento

- Fazer o primeiro deploy e confirmar inicialização do profile `prod`, health check do host, migrations Flyway e conexão TLS ao Postgres.
- Validar cadastro, confirmação e recuperação de conta com Resend real; login Google se estiver habilitado; cookies, CSRF, CORS e domínios no navegador.
- Fazer upload de import, confirmar leitura do R2 depois de reiniciar a API, testar retry e limpeza, e validar arquivo de 50 MiB dentro dos limites HTTP do Heroku.
- Confirmar que o worker de imports inicia em 5 segundos após terminar cada execução e que a geração dos jogos mantém o agendamento de 10 minutos.
- Configurar backup diário com uma cópia externa criptografada e testar a restauração.
- Medir memória e latência com cache, importação e jogos ativos. Não há evidência de capacidade para 100 usuários até executar esse teste.

## 4. Não escalar antes da validação

- Concluir o Gate R10 em ambiente adequado: limitadores compartilhados, jobs, reinícios, locks e concorrência. Até lá, manter uma réplica.
- Revisar segurança antes de abrir cadastro público e executar uma varredura de vulnerabilidades.
- O projeto ainda não tem Actuator nem pipeline CI/CD; definir o health check aceito pelo host e um fluxo repetível de publicação.
- A política de hosts permitidos para `profilePicture` e `banner` continua pendente; o backend não baixa essas imagens.

## Situação do código

- `/search` está implementado e o repositório tem 95 migrations Flyway.
- Confirmação de e-mail, recuperação de senha e listagem de pedidos de follow enviados estão implementadas. A dependência externa pendente é configurar e testar o Resend na aplicação publicada.
- O adaptador R2, a configuração de produção, os limites de conexão e as três políticas de cache estão no código. Isso não comprova acesso ao bucket real, persistência após reinício, capacidade do dyno ou funcionamento do app publicado.
- A OpenAPI e os documentos em `docs/` permanecem apenas na máquina local e ignorados pelo Git, conforme decisão do usuário.