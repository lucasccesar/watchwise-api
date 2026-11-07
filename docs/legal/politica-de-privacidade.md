# Política de Privacidade do Watchwise

**Última atualização:** 4 de novembro de 2026

Esta Política explica como o Watchwise trata dados pessoais no site e, quando disponibilizado, no aplicativo Android. O controlador é **Lucas César**, pessoa natural no Brasil. Para exercer direitos ou tirar dúvidas, escreva para [support@watch-wise.app](mailto:support@watch-wise.app). Esse é o canal de privacidade do Watchwise.

## 1. Dados tratados

O Watchwise trata, conforme o recurso utilizado:

- **Cadastro e conta:** nome, nome de usuário, e-mail, senha protegida, foto e banner por URL, descrição do perfil, preferências de idioma/região, configuração de visibilidade, confirmação de e-mail e dados técnicos de sessão.
- **Atividade no serviço:** avaliações, resenhas, comentários e respostas, listas, registros de diário, itens de acompanhamento, curtidas, seguidores, bloqueios, seleções de Picks, notificações e configurações de perfil.
- **Denúncias e suporte:** motivo e conteúdo da denúncia, material identificado, dados da pessoa denunciada, comunicações de suporte e decisões de moderação ou contestação.
- **Segurança e funcionamento:** endereço IP e registros técnicos de requisições, data e hora, dispositivo/navegador quando disponíveis, tentativas de autenticação, erros e dados necessários para prevenir fraude, abuso e acesso indevido.
- **Importação de histórico:** arquivos que você enviar e dados extraídos deles para montar uma prévia de importação. Os arquivos de importação são configurados para expirar após 24 horas; a rotina de limpeza pode concluir a remoção depois desse momento.
- **Login Google:** identificador único da conta Google e e-mail verificado, usados para autenticação. O Watchwise não importa nome nem foto do Google e não recebe a senha Google.
- **Android e push, quando disponibilizados:** token de dispositivo do Firebase Cloud Messaging (FCM), preferências de notificação e dados necessários para entregar notificações. A notificação poderá mostrar o nome de quem interagiu e o conteúdo relacionado, inclusive texto de comentário. A pessoa poderá desligar push nas configurações do Watchwise ou do Android e ocultar prévias com conteúdo.
- **Monitoramento técnico, quando ativado:** métricas de desempenho e da JVM, tempos de transação e informações técnicas de erros enviados ao New Relic. A configuração planejada desativa o encaminhamento de logs, remove mensagens de exceção e não adiciona atributos com conteúdo de comentários, credenciais ou identificadores de usuários. Rastros técnicos ainda podem conter nomes de rotas e detalhes necessários ao diagnóstico.

O Watchwise não pede dados sensíveis como condição para usar o serviço. Evite publicar dados sensíveis seus ou de terceiros em perfis, comentários ou resenhas.

## 2. Para que usamos os dados

Usamos dados para:

1. criar e manter a conta, autenticar usuários e fornecer os recursos sociais e de acompanhamento;
2. exibir o perfil e o conteúdo segundo as configurações de visibilidade;
3. enviar mensagens indispensáveis à conta, como confirmação de e-mail, recuperação de acesso e avisos de segurança;
4. gerar notificações dentro do serviço e, no Android, entregar push quando essa opção estiver habilitada;
5. consultar o catálogo do TMDB e apresentar informações sobre filmes, séries, temporadas, episódios e pessoas;
6. receber e analisar denúncias, remover conteúdo proibido, aplicar as Diretrizes da Comunidade e tratar contestações;
7. prevenir fraude, abuso, falhas técnicas e acessos não autorizados;
8. cumprir obrigações legais, atender requisições válidas e proteger direitos.

No lançamento não haverá anúncios, publicidade direcionada, newsletter nem analytics de comportamento. O e-mail será usado para comunicações necessárias à conta. O New Relic, quando ativado, será usado para monitoramento técnico, não para publicidade ou perfilamento de usuários.

## 3. Bases legais

O tratamento será apoiado, conforme cada finalidade, na execução do serviço solicitado, no cumprimento de obrigação legal, no exercício regular de direitos, em legítimo interesse para segurança e moderação, ou em consentimento quando exigido. O consentimento para transferência internacional, descrito abaixo, é separado da aceitação dos Termos e desta Política.

## 4. Visibilidade do perfil e conteúdo

O perfil é público por padrão. Você pode torná-lo privado nas configurações disponíveis. Comentários, resenhas, avaliações, listas e demais interações podem ficar visíveis conforme a privacidade do perfil e as opções de cada recurso. Conteúdo público pode ser copiado ou compartilhado por terceiros fora do controle do Watchwise.

## 5. Cookies

O site usa cookies estritamente necessários para manter a autenticação e proteger requisições contra falsificação. O cookie de autenticação é configurado como não acessível a scripts da página. No lançamento, o Watchwise não usa cookies para publicidade nem analytics de comportamento.

## 6. Provedores e compartilhamento

O Watchwise compartilha dados apenas no necessário para as finalidades informadas:

| Provedor | Uso previsto |
|---|---|
| **Heroku / Salesforce** | Hospedagem da API e do banco PostgreSQL, inicialmente nos Estados Unidos. |
| **Cloudflare R2** | Armazenamento temporário dos arquivos usados na importação de histórico. A configuração prevista é a jurisdição dos Estados Unidos, que deve ser selecionada ao criar o bucket ([informações de localização do R2](https://developers.cloudflare.com/r2/reference/data-location/)). |
| **Google** | Login com Google no site e FCM para notificações push no Android. O FCM recebe o token do dispositivo e o conteúdo necessário para entregar a notificação. |
| **Resend** | Envio de e-mails transacionais, como confirmação de cadastro e recuperação de acesso. A Resend informa que armazena dados de e-mail nos Estados Unidos ([segurança e localização de dados](https://resend.com/security)). |
| **TMDB** | Consulta de títulos e metadados do catálogo. Consultas e identificadores de conteúdo podem ser enviados para obter esses dados. |
| **New Relic, quando ativado** | Métricas técnicas, desempenho, erros e informações da JVM. A região de dados padrão é a dos Estados Unidos; o provedor também pode acessar ou processar dados em outras jurisdições para suporte e operação ([regiões de dados](https://docs.newrelic.com/docs/accounts/accounts-billing/account-setup/choose-your-data-center/)). |
| **Provedor da caixa de suporte** | Recebimento e resposta a mensagens enviadas para support@watch-wise.app. |

Esses provedores podem usar operadores técnicos próprios para prestar seus serviços. O Watchwise não vende dados pessoais nem os compartilha para publicidade direcionada.

## 7. Transferências internacionais

O backend, o banco PostgreSQL e, conforme a configuração planejada, os arquivos temporários do Cloudflare R2 ficam nos Estados Unidos. Resend também informa que armazena dados de e-mail nos Estados Unidos. Alguns provedores podem acessar ou tratar dados em outros países para suporte, segurança, operação e subprocessamento. Nesses casos, o Watchwise adotará um mecanismo válido de transferência internacional previsto na LGPD e no Regulamento de Transferência Internacional da ANPD.

Quando a transferência depender de consentimento, ele será solicitado **antes do envio dos dados**, de forma específica e destacada, em uma opção separada da aceitação dos Termos. Você pode revogar esse consentimento pelo e-mail [support@watch-wise.app](mailto:support@watch-wise.app). A revogação pode impedir o uso de recursos que dependam do processamento internacional; o Watchwise avaliará a migração para outro destino autorizado ou o encerramento e a exclusão da conta, conforme a lei.

### Texto separado de consentimento para a tela de cadastro

> **Transferência internacional de dados:** Autorizo, de forma específica e destacada, que os dados pessoais necessários à minha conta e ao funcionamento do Watchwise sejam transferidos e tratados nos Estados Unidos e, quando necessário, em outros países onde operem os provedores indicados nesta Política, incluindo Heroku/Salesforce, Google, Cloudflare, Resend, TMDB e New Relic quando ativado. Entendo que isso é necessário para hospedagem, autenticação, envio de e-mails, notificações, importação e monitoramento técnico. Posso revogar este consentimento pelo canal de privacidade; a revogação pode impedir a continuidade de recursos que dependam desses provedores.

Essa opção deve ser exibida separadamente e não pode vir pré-selecionada.

## 8. Retenção e exclusão

Mantemos dados enquanto a conta estiver ativa e pelo tempo necessário às finalidades descritas, à segurança e ao cumprimento da lei. Aplicamos, entre outros, estes prazos operacionais:

- arquivos de importação: expiração configurada após 24 horas, seguida da rotina de limpeza;
- notificações sociais lidas: remoção automática depois de 7 dias;
- notificações sociais não lidas: remoção automática depois de 30 dias.

Ao excluir a conta, o Watchwise apaga os dados e conteúdos ligados a ela, inclusive os comentários da conta e as respostas abaixo deles. Não mantém esses comentários com o autor oculto. Alguns registros podem ser conservados quando uma lei exigir, para cumprir ordem válida ou para estabelecer, exercer ou defender direitos. Quando a conservação deixar de ser necessária, os dados serão eliminados ou anonimizados.

Registros técnicos podem ser mantidos pelo tempo necessário à segurança e conforme obrigações legais aplicáveis. O prazo pode variar conforme o tipo de registro e a infraestrutura do provedor.

## 9. Seus direitos

Você pode solicitar confirmação e acesso ao tratamento, correção, anonimização, bloqueio ou eliminação de dados desnecessários ou tratados em desconformidade, portabilidade nos termos da regulamentação, informação sobre compartilhamentos e revogação de consentimento. Envie a solicitação para [support@watch-wise.app](mailto:support@watch-wise.app). Podemos pedir informações razoáveis para confirmar sua identidade. Você também pode peticionar à Autoridade Nacional de Proteção de Dados (ANPD).

## 10. Segurança e incidentes

O Watchwise aplica controles técnicos e organizacionais compatíveis com os dados tratados. Nenhum serviço conectado à internet pode prometer segurança absoluta. Se ocorrer incidente que possa causar risco ou dano relevante, o Watchwise adotará as providências e comunicações exigidas pela LGPD e pelas regras da ANPD.

## 11. Crianças e adolescentes

O Watchwise exige idade mínima de 18 anos e não foi concebido para contas de crianças ou adolescentes. A declaração de idade não afasta obrigações de proteção que possam se aplicar conforme as funcionalidades e o acesso provável ao serviço. Se identificarmos uma conta operada por pessoa abaixo dessa idade, poderemos restringi-la e excluí-la, observando as obrigações legais de proteção de crianças e adolescentes. Denúncias relacionadas a direitos de crianças e adolescentes podem ser enviadas pelo canal de denúncia do serviço ou ao e-mail de suporte.

## 12. Atualizações e legislação

Esta Política pode ser atualizada para refletir mudanças no serviço, nos provedores ou na legislação. Alterações materiais serão destacadas no site ou comunicadas pelos meios disponíveis. Esta Política é complementada pela [LGPD](https://www.planalto.gov.br/ccivil_03/_ato2015-2018/2018/lei/l13709.htm), pelo [Regulamento de Transferência Internacional de Dados da ANPD](https://www.gov.br/anpd/pt-br/acesso-a-informacao/institucional/atos-normativos/regulamentacoes_anpd/resolucao-cd-anpd-no-19-de-23-de-agosto-de-2024), pelo [Marco Civil da Internet](https://www.planalto.gov.br/ccivil_03/_ato2011-2014/2014/lei/l12965.htm) e pelo [ECA Digital](https://www.planalto.gov.br/ccivil_03/_ato2023-2026/2025/lei/l15211.htm).
