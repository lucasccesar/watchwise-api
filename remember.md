# Padrões recorrentes — coisas que já corrigi e voltaram a acontecer

Levantamento feito em 2026-08-22 sobre os 46 commits `fix(...)` do histórico do projeto.
Objetivo: identificar categorias de bug que não foram corrigidas "de uma vez por todas" —
voltaram em outro módulo, ou na própria correção, dias/semanas depois. A ideia é reconhecer
o padrão da próxima vez, antes de escrever o código, não depois de outro bug.

## 1. Handler de erro incompleto — o mesmo buraco em lugares diferentes (4 rodadas + 1 hoje)

- `d27771d` (08-08) — `GlobalExceptionHandler` nem estava registrado como bean.
- `a29633b` (08-08) — access-denied (CSRF ausente) não devolvia JSON.
- `3319b16` / `3c014a9` (08-09) — erros de framework e parâmetros ausentes sem tratamento
  consistente.
- `cd61c75` / `4297dc6` (08-17) — rotas não mapeadas e enum inválido sem `ApiError`.
- **22/08 (hoje)** — auditoria encontrou que ainda falta um `@ExceptionHandler(Exception.class)`
  genérico; qualquer exceção não mapeada ainda cai no formato padrão do Spring Boot.

**Por que recorre:** cada fix cobre o caso específico que quebrou naquele momento
("ah, faltava tratar X"), nunca a pergunta "e o que cai fora de todos os handlers que eu já
tenho?". Vira uma lista de casos especiais em vez de uma rede com fallback.

**Como evitar:** toda vez que mexer no `GlobalExceptionHandler`, adicionar/confirmar um
handler genérico de último recurso que devolve `ApiError` para qualquer coisa não mapeada —
e tratar isso como parte obrigatória do handler, não como mais um caso a acrescentar depois.

## 2. Corridas (race conditions) tratadas caso a caso, nunca com um padrão único

- `accde57` (08-15) — get-or-create de content/followedperson isolado em nova transação.
- `8da639a` (08-17) — re-query do natural key antes de mapear nome de constraint
  (`resolveConcurrentCreation`).
- `e23cf7e` (08-17) — detectar rotação concorrente de refresh token.
- `b90c543` (22/08, hoje) — `AttemptLockout` — check-then-act não atômico.
- **Ainda abertas na auditoria de hoje:** `DroppedEntryServiceImpl.markAsDropped` (efeito
  colateral não atômico entre remover da watchlist e criar o dropped entry) e
  `UserServiceImpl.updateUser` (aceite de follow requests comita antes do `save` do usuário).

**Por que recorre:** cada módulo resolve a concorrência "na mão", com a solução que fez
sentido pontualmente (retry, `REQUIRES_NEW`, re-query, mapa atômico) — não existe um
checklist aplicado a features novas do tipo "toda operação com efeito colateral externo e
possibilidade de falha precisa ser avaliada quanto a atomicidade".

**Como evitar:** ao escrever qualquer service que (a) modifica mais de uma
entidade/agregado, ou (b) faz "check → operação lenta → grava resultado", perguntar
explicitamente ANTES de considerar pronto: "o que acontece se duas chamadas concorrentes
passarem pelo check ao mesmo tempo?" e "o que acontece se a segunda escrita falhar depois
que a primeira já comitou?".

## 3. Efeito colateral entre features irmãs (watchlist/dropped/diário) implementado duas vezes, ad hoc

- `eb8d49b` (08-18) — logar no diário remove entradas correspondentes de watchlist/dropped.
- `d2ab5a6` (08-19, um dia depois) — marcar como dropped remove da watchlist. Mesma regra de
  negócio ("um content só pode estar em um desses estados por vez"), implementada
  separadamente em cada feature.
- A auditoria de hoje mostra que a implementação de `d2ab5a6` ainda carrega o gap de
  atomicidade do item 2 acima — ou seja, o mesmo pedaço de regra foi corrigido, mas não de
  forma robusta.

**Por que recorre:** a regra "watchlist / dropped / diário são mutuamente exclusivos" não
vive em um lugar só; cada feature nova que participa dela precisa lembrar de implementar a
limpeza cruzada, e isso só costuma aparecer depois que alguém percebe a inconsistência de
dados.

**Como evitar:** quando a regra de negócio envolve múltiplas features ("X só pode estar em
um destes estados"), deixar isso explícito (nome de método, comentário curto apontando os
outros lugares afetados), e na próxima feature que tocar esse mesmo estado, checar as que já
existem antes de implementar do zero.

## 4. Validação de campo esquecida em DTOs novos, corrigida uma feature de cada vez

- `8ead8a6` (08-16) — rejeitar números não-positivos em content/diaryentry.
- `d5101c5` (08-17) — `personTmdbId` precisa ser numérico e ter no máximo 20 dígitos.
- `9642ccf` (08-20) — limitar comentário do `DroppedEntry` a 280 caracteres.
- `ac4abcd` (08-17) — rejeitar username menor que 3 caracteres após trim.
- **Ainda aberto na auditoria de hoje:** `finaleSeasonNumber`/mapa de episódios sem `@Max`;
  `pageSize > 1000` sendo silenciosamente descartado para o default em vez de rejeitado.

**Por que recorre:** validação é adicionada reativamente (alguém manda um valor absurdo,
algo quebra, você corrige aquele campo específico), nunca como uma revisão sistemática do
DTO inteiro no momento em que ele é criado.

**Como evitar:** ao criar um DTO de request novo, revisar todos os campos
numéricos/string de uma vez (min/max, tamanho, formato) antes do primeiro commit, em vez de
corrigir um campo por vez conforme os bugs aparecem.

## 5. Regra de segurança aplicada a um endpoint, esquecida no endpoint irmão

- `a7357c2` (08-09) — rejeitar login se já autenticado.
- `fdb032a` (08-09, mesmo dia, commit separado) — mesma regra aplicada ao register.

Contraste: `6e782456` (08-20) — restringir `type` a MOVIE/SERIES já saiu corrigindo
watchlist + dropped + top5 **no mesmo commit**. Ou seja, a lição já foi aplicada uma vez
("checar todos os endpoints irmãos"), mas não virou hábito fixo — o caso do login/register
foi dois commits separados no mesmo dia porque o segundo só foi lembrado depois.

**Como evitar:** toda vez que uma regra de segurança/validação for aplicada a um endpoint,
perguntar "quais outros endpoints fazem algo parecido?" antes de fechar o commit — quando
essa pergunta é feita (como em `6e782456`), o fix já sai completo de primeira.

---

## Resumo rápido para consulta futura

Antes de considerar uma feature pronta, passar por esta lista:

1. Alguma exceção nova pode escapar do `GlobalExceptionHandler` sem virar `ApiError`?
2. Existe efeito colateral em outra entidade/service? Se sim, o que acontece se a segunda
   escrita falhar depois que a primeira já comitou? E sob concorrência?
3. Essa feature participa de alguma regra "X só pode estar em um estado entre várias
   features"? As features irmãs já tratam isso — a nova também trata?
4. Todo campo numérico/string do DTO tem limite (`@Min`/`@Max`/tamanho/formato) definido?
5. Essa regra de segurança/validação se aplica a outro endpoint irmão que ainda não foi
   tocado?
</content>
