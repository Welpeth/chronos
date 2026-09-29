# Changelog

As versões seguem o `<version>` do `pom.xml`. Cada merge na `main` com uma versão nova gera a release
`v<versão>` no GitHub, com o instalador e a versão portátil, e usa a seção dessa versão abaixo como notas.

## [Não lançado]

### Adicionado
- Opção "Mostrar todas as tasks dessas colunas, de qualquer responsável" (aba Colunas): a lista traz também as
  tasks das colunas que contam tempo que estão com outra pessoa ou sem responsável, por exemplo tudo em "Test"
  para quem testa. Elas mostram o responsável e só contam tempo pelo play.

### Alterado
- Configurações separadas em abas no topo (Jira, Colunas, Tempo, Avisos, Histórico, Sistema), no estilo das
  abas do navegador, sem fechar. O botão Salvar grava todas as abas.

### Corrigido
- Tempo em dobro no apontamento (2 min contados em 1 min de trabalho): abrir o Chronos de novo com ele na
  bandeja criava um segundo app contando as mesmas tasks no mesmo histórico. Agora só um Chronos roda; abrir
  outra vez mostra a janela do que já está aberto.

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

[0.2.0]: https://github.com/Welpeth/chronos/compare/v0.1.2...v0.2.0
[0.1.2]: https://github.com/Welpeth/chronos/releases/tag/v0.1.2
