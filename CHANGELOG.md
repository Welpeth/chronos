# Changelog

As versões seguem o `<version>` do `pom.xml`. Cada merge na `main` com uma versão nova gera a release
`v<versão>` no GitHub, com o instalador e a versão portátil, e usa a seção dessa versão abaixo como notas.

## [Não lançado]

### Adicionado
- Filtro por quadro (Configurações > Jira, ou `JIRA_BOARD_FILTERS` no `.env`): um JQL a mais para cada quadro,
  para separar quadros que pegam as mesmas tasks, como dois quadros com `project = RP`. Por exemplo,
  `514: Categoria = JONATHAN; 215: Categoria is EMPTY`. A task só entra no quadro se também atender ao filtro.

### Corrigido
- Quadros com o mesmo nome no Jira (como dois "Quadro RP") viravam um só, e os chips do Painel não apareciam.
  Agora cada um ganha o número no fim, "Quadro RP (215)" e "Quadro RP (514)".
- O campo Quadros aceita "215 e 514", "215 514" ou "215; 514", além de "215, 514". Antes, só a vírgula
  separava, e o segundo quadro ficava de fora.
- Os chips mostram todos os quadros configurados, mesmo os que ainda não têm task sua.
- "Testar conexão" mostra quantas das suas tasks cada quadro tem, para conferir se os quadros estão certos.

## [0.5.0] - 2026-09-30

### Adicionado
- Separação por quadro (Configurações > Jira > Quadros, ou `JIRA_BOARDS` no `.env`): com os números ou endereços
  dos quadros, por exemplo 215 e 514 do mesmo projeto, o seletor do topo passa a listar os quadros pelo nome, e
  cada tela mostra só as tasks do quadro escolhido, inclusive nos dias anteriores do histórico.
- Comentários das tasks (Configurações > Comentários, ou `CHRONOS_COMMENTS=true`): nova aba abaixo de
  Apontamentos com as tasks suas nas colunas monitoradas que faltam comentar, um template em Markdown com emojis
  do Jira por extenso (`:light_bulb_on:`) e o histórico dos comentários, que podem ser editados. Salvar publica
  o comentário no Jira. Com "Habilitar template padrão" (`CHRONOS_COMMENT_TEMPLATE=true`), o template vai sozinho
  para cada task sua que entra numa coluna monitorada e ela fica como "Template adicionado".
- Chips de quadro no Painel, no lugar do seletor do topo: "Todos os quadros" e um chip por quadro, podendo
  marcar vários. A escolha vale para todas as páginas. Cada task mostra na lista os quadros em que está.
- Filtro de Tarefas pela data de modificação no Jira (qualquer data, hoje, ontem, últimos 7 ou 30 dias). Cada
  task mostra na lista quando foi modificada.
- Clicar numa task em "Tarefas do projeto" (ou no "Mostrar mais") traz ela para o cartão "Task atual", com o
  play dela. "Voltar para ..." devolve a task que está contando.
- Abas "Visão geral" e "Visão kanban" no Painel. O kanban mostra as tasks nas colunas dos quadros do Jira (dos
  quadros escolhidos nos chips), com o tempo e o play de cada uma; clicar num cartão abre a task em "Task
  atual". Sem acesso às colunas do quadro, agrupa pelo status.

### Corrigido
- As colunas digitadas em "Colunas que contam tempo" agora valem pelo nome da coluna no quadro, mesmo quando o
  status das tasks nela tem outro nome (a coluna "Test" que mostra o status "Em teste", por exemplo). O Chronos
  lê as colunas dos quadros de `JIRA_BOARDS` ou, sem eles, dos quadros dos projetos. Maiúsculas, acentos e
  espaços repetidos não fazem mais diferença.
- O filtro de quadros segue o que o quadro mostra: a task precisa estar num status de alguma coluna do quadro
  e atender ao sub-filtro do Kanban. Uma task em dois quadros aparece nos dois, e uma task que saiu de todos
  os quadros deixa de aparecer no quadro antigo.
- As colunas da Visão kanban seguem a ordem do quadro no Jira. Com mais de um quadro, a coluna que só um deles
  tem (como "Code Review") entra no lugar dela, antes de "Concluído", em vez de ir para o fim.

## [0.4.0] - 2026-09-29

### Adicionado
- Seletor de projeto no topo da janela, quando as tasks vêm de mais de um projeto do mesmo Jira (por exemplo
  dois quadros): o painel, as tarefas, o histórico e os apontamentos mostram só o projeto escolhido, com os
  totais do dia só dele. O tempo continua contando em todos, e a escolha fica guardada. (#37)

## [0.3.0] - 2026-09-29

### Adicionado
- Opção "Mostrar todas as tasks dessas colunas, de qualquer responsável" (aba Colunas): a lista traz também as
  tasks das colunas que contam tempo que estão com outra pessoa ou sem responsável, por exemplo tudo em "Test"
  para quem testa. Elas mostram o responsável e só contam tempo pelo play. (#29)
- Tela Tarefas em abas: "Geral", com todas, e "Colunas monitoradas", só com as que estão nas colunas que contam
  tempo. (#30)
- Opção "Só aceitar tempo nas tasks que estão nessas colunas" (aba Colunas, desligada por padrão): as tasks do
  Jira fora das colunas ficam sem play e sem tempo manual. (#30)
- Tags da validação (aba Categorias): ao começar o tempo de uma task, o Chronos põe nela as tags do play (ex.:
  em-teste) no campo Labels do Jira; quando ela sai das colunas monitoradas ou é finalizada, tira essas e põe
  as de terminar (ex.: testado). Falhas aparecem em Atividade recente. (#31)
- Modo escuro (Configurações > Sistema), aplicado na hora ao salvar. (#32)
- Atualizar pelo app (Configurações > Sistema > Procurar atualização): procura a última release no GitHub,
  pergunta, copia o histórico para `backup/chronos-<versão>.db`, baixa o instalador e fecha para instalar. (#33)
- Restaurar base histórica (Configurações > Histórico): escolhe uma cópia da pasta `backup`, que vira o
  histórico atual, e o Chronos reinicia. O histórico que estava em uso também fica guardado em `backup`. (#33)
- Idioma da interface (Configurações > Sistema): português, inglês ou espanhol. Troca ao reiniciar. (#34)

### Alterado
- Configurações separadas em abas no topo (Jira, Colunas, Tempo, Avisos, Histórico, Sistema), no estilo das
  abas do navegador, sem fechar. O botão Salvar grava todas as abas. (#28)

### Corrigido
- Tempo em dobro no apontamento (2 min contados em 1 min de trabalho): abrir o Chronos de novo com ele na
  bandeja criava um segundo app contando as mesmas tasks no mesmo histórico. Agora só um Chronos roda; abrir
  outra vez mostra a janela do que já está aberto. (#27)

## [0.2.0] - 2026-09-28

### Adicionado
- Detecção de atividade no Windows: o teclado e o mouse do PC inteiro contam como atividade. Depois de
  2 min sem mexer, o painel mostra "Possivelmente ausente" e o tempo segue contando; depois de 5 min, as
  tasks pausam e o período vira tempo ocioso. Com a tela bloqueada acontece o mesmo. (#23)
- Este changelog, e as releases passam a usar a versão do `pom.xml`.

## [0.1.2] - 2026-09-28

Primeira versão instalável.

### Adicionado
- EXE do Windows: instalador por usuário (sem administrador) e versão portátil em `.zip`, com o Java
  incluso, gerados pelo GitHub Actions a cada merge na `main`. No app instalado, o `.env` e o histórico
  ficam em `%APPDATA%\Chronos`. (#22)
- Opções em Configurações para somar colunas digitadas às colunas padrão (por exemplo "Test") e para o
  tempo começar sozinho ou só pelo play. (#21)
- Listas do painel mais compactas, com "Mostrar mais" numa janela com 25 itens por página. (#20)
- Histórico, totais e apontamentos separados por Jira. (#19)
- Data de validade do API token em Configurações, com aviso quando estiver perto de vencer. (#18)
- Logo novo, depois na versão horizontal no topo. (#10, #17)
- Página Apontamentos: lança no "Controle de tempo" do Jira o tempo contado de cada task, com aviso
  quando o quadro não tem esse campo. (#15, #16)
- Avisos de tasks novas de tipos escolhidos (por exemplo "Bug Cliente"), com notificação do Windows e
  bolinha vermelha no ícone. (#13)
- Ícone na bandeja do Windows, com pausar e finalizar tasks. (#12)
- Tela de Configurações que grava o `.env`, com "abrir ao entrar no Windows". (#11)
- Tempo conta só nas colunas configuradas; a task pausa ao mudar de coluna. (#9)
- Tempo manual com limite de 8h por dia, histórico por data com busca e tempo ocioso. (#5 a #8)
- Painel novo, páginas Tarefas e Histórico, várias tasks contando em paralelo com play e pausa. (#2 a #4)
- Cliente do Jira e o esqueleto do app em JavaFX. (#1)

### Corrigido
- O histórico de um Jira antigo aparecia depois de trocar o Jira nas configurações. (#19)
- O aviso de "controle de tempo" aparecia em projetos gerenciados pela equipe que tinham o campo. (#19)

[0.5.0]: https://github.com/Welpeth/chronos/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/Welpeth/chronos/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/Welpeth/chronos/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/Welpeth/chronos/compare/v0.1.2...v0.2.0
[0.1.2]: https://github.com/Welpeth/chronos/releases/tag/v0.1.2
