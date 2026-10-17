# Picks

A feature **Picks** permite que a aplicação disponibilize templates compostos por categorias predefinidas, que podem ser preenchidas individualmente pelos usuários com conteúdos ou pessoas cadastrados na plataforma.

Embora possa ser utilizada para representar premiações como Oscar, Globo de Ouro e Emmy, a estrutura deve ser genérica o suficiente para permitir outros tipos de templates no futuro, como “Sobre você”, “Favoritos do ano”, “Superestimados e subestimados” e formatos semelhantes.

## Estrutura de um template de Pick

Um template de Pick é criado por um administrador ou por alguém com permissão para gerenciar
esse conteúdo. O Pick é o preenchimento criado por um usuário autenticado.

Cada template deve possuir, no mínimo:

- nome;
- descrição;
- imagem/capa, se aplicável;
- período de elegibilidade;
- conjunto de categorias;
- informações adicionais necessárias para apresentação no front-end.

Exemplo:

**Nome:** Globo de Ouro 2026  
**Descrição:** Escolha seus vencedores para o Globo de Ouro 2026.  
**Período de elegibilidade:** julho de 2025 até junho de 2026.

Quando preenchido, o período de elegibilidade define quais conteúdos podem ser considerados
em todas as categorias de conteúdo do template. Categorias de pessoa não aplicam essa regra.

A validação deve considerar a **data de lançamento global** do conteúdo.

---

# Categorias

Cada template é composto por diversas categorias.

Exemplos:

- Melhor Filme de Drama;
- Melhor Filme de Comédia ou Musical;
- Melhor Ator em Filme de Drama;
- Melhor Atriz em Filme de Drama;
- Melhor Série de Drama;
- Melhor Episódio;
- etc.

As categorias podem ser divididas em dois grupos:

- **principais**;
- **secundárias**.

Essa classificação existe principalmente para organização e apresentação visual da página.

Nenhuma categoria deve ser obrigatória. O usuário pode preencher apenas as categorias que desejar.

Cada categoria deve definir qual tipo de entidade pode ser selecionado.

Tipos inicialmente suportados:

- filme;
- série;
- pessoa;
- episódio.

Exemplo:

**Melhor Filme de Drama**  
Tipo permitido: `MOVIE`

**Melhor Ator em Filme de Drama**  
Tipo permitido: `PERSON`

**Melhor Série de Drama**  
Tipo permitido: `SERIES`

**Melhor Episódio**  
Tipo permitido: `EPISODE`

O sistema não deve permitir que uma entidade de outro tipo seja associada à categoria.

---

# Elegibilidade por período

O período de elegibilidade pertence ao template e é opcional.

Por exemplo:

**Globo de Ouro 2026**

Período:

`2025-07-01 → 2026-06-30`

Se a categoria for:

**Melhor Filme de Drama**

o usuário poderá selecionar somente filmes cuja data de lançamento global esteja dentro desse intervalo.

Uma produção lançada antes ou depois desse período não deverá aparecer como opção válida e não poderá ser salva como escolha do usuário.

A existência das duas datas aplica obrigatoriamente a restrição a todas as categorias de
conteúdo:

- filmes;
- séries;
- episódios.

Categorias do tipo `PERSON` nunca aplicam o período, mesmo quando possuem um conteúdo
contextual. O contexto pode ser validado por tipo e existência, mas sua data não participa
da elegibilidade.

Se o template não tiver o período completo, nenhuma categoria terá restrição de data. Datas
incompletas ou invertidas devem ser rejeitadas.

---

# Categorias abertas e categorias com indicados

Cada categoria pode funcionar em dois modos.

O modo deve ser informado explicitamente no template. Uma categoria `FIXED` pode ser criada
sem opções e permanecerá sem possibilidade de preenchimento até que uma opção seja
cadastrada. Uma categoria `OPEN` não possui opções fixas: seus alvos são pesquisados de
acordo com o tipo permitido.

## Categoria aberta

Utilizada principalmente antes da divulgação oficial dos indicados.

Nesse modo, o usuário pode pesquisar livremente dentro da base da aplicação, respeitando:

- o tipo definido pela categoria;
- o período de elegibilidade do template, quando existir e a categoria for de conteúdo;
- outras restrições eventualmente configuradas.

Categorias do tipo `PERSON` também podem ser abertas. Nesse caso, a pesquisa retorna pessoas
diretamente do TMDB e a pessoa poderá ser selecionada sem estar previamente cadastrada como
opção fixa. Um conteúdo contextual opcional continua permitido para pessoas.

Exemplo:

**Melhor Filme de Drama**

Pode selecionar qualquer:

`MOVIE`

desde que o filme esteja dentro do período de elegibilidade do Pick.

## Categoria limitada por indicados

Quando os indicados oficiais forem divulgados, o administrador poderá adicionar uma lista de opções permitidas para determinada categoria.

Nesse caso, somente essas opções poderão ser selecionadas.

Exemplo:

**Melhor Filme de Drama**

Indicados:

- Filme A;
- Filme B;
- Filme C;
- Filme D;
- Filme E.

O usuário poderá escolher somente um desses filmes, mesmo que existam outros filmes elegíveis pelo período.

Portanto, os indicados funcionam como uma restrição adicional sobre a categoria.

Conceitualmente:

`tipo permitido + período permitido + indicados permitidos`

Quando existir uma lista de indicados, ela terá prioridade sobre a pesquisa aberta.

---

# Pesquisa

Cada categoria deve possuir seu próprio mecanismo de pesquisa.

A pesquisa deve considerar automaticamente o tipo configurado para aquela categoria.

Por exemplo:

Categoria:

**Melhor Filme de Drama**

Tipo:

`MOVIE`

Ao selecionar essa categoria, a pesquisa deve procurar exclusivamente filmes.

Categoria:

**Melhor Ator em Filme de Drama**

Tipo:

`PERSON`

A pesquisa deve retornar exclusivamente pessoas.

Categoria:

**Melhor Episódio**

Tipo:

`EPISODE`

A pesquisa deve retornar exclusivamente episódios.

Além do tipo, a busca deve aplicar as restrições configuradas para a categoria, como período de elegibilidade e lista de indicados.

---

# Picks do usuário

Cada usuário pode criar uma quantidade ilimitada de Picks independentes para o mesmo
template. Cada Pick possui seu próprio identificador, visibilidade e conjunto de seleções.

Por exemplo, o template `Globo de Ouro 2026` pode possuir vários Picks do mesmo usuário,
além dos Picks de outros usuários.

As escolhas devem ser persistidas para que cada Pick possa ser consultado e editado
posteriormente.

Uma resposta do usuário pode ser representada conceitualmente como:

`Usuário + Pick + Categoria + Escolha`

Exemplo:

`Lucas + Pick 42 + Melhor Filme de Drama + Filme X`

Nenhuma categoria é obrigatória, mas um Pick só pode ser salvo quando tiver pelo menos uma
seleção válida. Depois de criado, ele deve continuar com pelo menos uma seleção persistida;
o usuário não pode remover a última seleção e, para descartar o preenchimento inteiro, deve
excluir o próprio Pick.

A criação de novos Picks terá throttling de duas tentativas por minuto por usuário. O limite
é aplicado por usuário autenticado e conta tentativas inválidas também.

## Edição, exclusão e desvinculação de templates

Enquanto um template não possuir Picks, o criador pode editá-lo e desvinculá-lo da própria
conta. Como não há respostas dependentes, a desvinculação exclui o template e suas
categorias e opções.

Depois que o template possuir pelo menos um Pick:

- ele não pode ser excluído;
- uma categoria que já tenha alguma seleção não pode ser alterada nem removida;
- uma opção fixa usada por alguma seleção não pode ser alterada nem removida;
- categorias e opções ainda não utilizadas podem ser administradas conforme as permissões;
- o criador pode desvincular o template da própria conta;
- ao desvincular, o template continua público, `creator` passa a ser `null` e somente um
  administrador pode administrá-lo;
- os Picks e as seleções existentes permanecem intactos.

A desvinculação não altera a origem do template, nem transforma as respostas existentes em
respostas de outro usuário.

---

# Comportamento da interface

Ao acessar a página de um Pick específico, o usuário deve visualizar inicialmente:

- nome;
- descrição;
- informações do Pick;
- período de elegibilidade do template, quando existir;
- categorias principais;
- categorias secundárias.

As categorias devem carregar junto com as escolhas já realizadas pelo usuário.

Quando uma categoria ainda não tiver sido preenchida, deve aparecer um estado vazio representado por um elemento clicável, por exemplo:

`[ + ]`

Ao clicar nesse elemento, deve ser aberta a interface de seleção correspondente à categoria.

A interface pode funcionar como modal, drawer ou tela específica de pesquisa.

A busca deve seguir automaticamente as regras da categoria.

Após selecionar uma opção, o estado vazio é substituído pelo conteúdo escolhido.

Exemplo:

Antes:

**Melhor Filme de Drama**

`[ + ]`

Depois:

**Melhor Filme de Drama**

`[ pôster ]`  
`Nome do Filme`

O usuário deve poder posteriormente:

- alterar sua escolha;
- remover uma escolha quando outra seleção permanecer no mesmo Pick;
- excluir o Pick inteiro.

Se a categoria for a única preenchida, a remoção deverá ser recusada para preservar a regra
de que todo Pick existente possui pelo menos uma seleção.

---

# Exemplo completo: Globo de Ouro 2026

Um administrador cria:

**Template de Pick:** Globo de Ouro 2026

Configura:

- nome;
- descrição;
- imagem;
- período de elegibilidade entre julho de 2025 e junho de 2026.

Depois adiciona categorias como:

**Melhor Filme de Drama**  
Tipo: Filme  
Grupo: Principal

**Melhor Ator em Filme de Drama**  
Tipo: Pessoa  
Grupo: Principal

**Melhor Série de Drama**  
Tipo: Série  
Grupo: Principal

**Melhor Filme Internacional**  
Tipo: Filme  
Grupo: Secundária

Antes da divulgação dos indicados, as categorias podem permanecer abertas, inclusive as
categorias de pessoa.

Nesse momento, um usuário que abrir “Melhor Filme de Drama” poderá pesquisar qualquer filme elegível lançado dentro do período determinado.

Quando os indicados forem divulgados, o administrador poderá transformar uma categoria
ainda não usada em limitada por indicados e cadastrar as opções oficiais. Depois que uma
categoria possuir seleções, sua estrutura e suas opções usadas não poderão ser alteradas ou
removidas.

A partir desse momento, a mesma categoria passa a aceitar exclusivamente aqueles filmes.

Quando um usuário acessa a página do Globo de Ouro 2026, o sistema recupera:

- os dados do template;
- suas categorias;
- suas restrições;
- seus indicados;
- os Picks daquele usuário para o template, quando solicitados pela listagem própria.

Categorias já preenchidas mostram a escolha atual.

Categorias ainda não preenchidas mostram o estado `[ + ]`, permitindo que o usuário faça sua seleção.

## Interações sociais e previews

`Pick` e `PicksTemplate` aceitam curtidas e comentários. A visibilidade do Pick também controla
essas interações: o dono vê Pick privado, seguidores aceitos veem Pick `FOLLOWERS` e qualquer
usuário autenticado pode interagir com Pick `PUBLIC`. Comentários mantêm resposta, spoiler e
remoção pelo autor; uma resposta só pode apontar para comentário do mesmo Pick ou template.

Templates e Picks expõem `likesCount`, `commentsCount` e `isLikedByViewer`. As rotas sociais são:

- `GET/POST /picks/{pickId}/comments`;
- `GET/POST /picks-templates/{templateId}/comments`;
- `POST/DELETE /picks/{pickId}/like`;
- `POST/DELETE /picks-templates/{templateId}/like`.

O preview de template inclui creator, nome, coverImage, descrição, data de criação, quantidade
de Picks visíveis para o viewer, quantidade de categorias, nomes de todas as categorias,
`myPicksCount` e `latestMyPickId`. Como o mesmo usuário pode ter vários Picks no template,
`latestMyPickId` aponta apenas para o mais recente e permite o botão “ver resposta”.

O preview de Pick inclui o preview do usuário e até cinco categorias respondidas, ordenadas por
`displayOrder`, com a seleção atual e `isValid`.
