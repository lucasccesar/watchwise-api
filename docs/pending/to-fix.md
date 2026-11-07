# Pendências atuais da API

Revisado em 2026-10-28 contra o código e o contrato local. A documentação fica apenas na máquina do usuário e não é versionada no remoto.

## Ainda em aberto

1. **Peso máximo em bytes dos caches Caffeine.** O código limita entradas, mas não o peso total. Escolher o valor depois de medir a memória do host.
2. **Política para URLs de `profilePicture` e `banner`.** O backend valida que são URLs, mas não restringe hosts; ele não baixa esses arquivos. Decidir se a lista de hosts permitidos é requisito do lançamento.
3. **Gate R10 antes de múltiplas réplicas.** `SharedLimiterStore` e locks estão implementados, mas falta comprovar operacionalmente limites compartilhados, jobs, reinícios e concorrência num deploy com mais de uma instância. Até lá, manter uma réplica.
4. **Varredura externa de vulnerabilidades e revisão pré-lançamento.** A revisão externa não foi executada nesta etapa, conforme a restrição informada pelo usuário.

Configuração de produção, provider Resend, storage durável, backups, health check e CI/CD estão detalhados em `pending-to-deploy.md`.

## Corrigido ou resolvido nesta consolidação

- `GET /search` está implementado.
- Confirmação de e-mail e recuperação de senha usam tokens de uso único; o adaptador Resend está no código. Faltam a chave, remetente e domínio verificado no ambiente de produção.
- A listagem de pedidos de follow enviados está implementada.
- O verificador Google distingue falhas do provedor de tokens inválidos.
- O mapa de ratings por episódio agora corresponde à paginação e metadata descritas na OpenAPI.
- A prévia `GET /diary/{id}/deletion-impact` e `overrideProtectedEntries` foram removidos do contrato local: no modelo vigente, exclusões removem eventos reais e invalidam projeções derivadas, sem cascata entre DiaryEntry.

## Decisões de produto fora do escopo atual

Recuperação de senha e pedidos de follow enviados foram incluídos no lançamento conforme a decisão do usuário. O envio de e-mails reais depende da configuração Resend no deploy.
