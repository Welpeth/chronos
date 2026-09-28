# Chronos — Jira Work Tracker

Aplicativo desktop (Windows) em Java 21 + JavaFX que acompanha sua atividade e registra
automaticamente o tempo gasto em cada issue do Jira.

## Instalando no Windows

Cada merge na `main` gera uma release no GitHub (aba **Releases**) com dois arquivos:

- `Chronos-<versão>.exe`: instalador. Instala só para o seu usuário, sem pedir administrador, e cria atalho no
  menu Iniciar e na área de trabalho. Uma versão nova instalada por cima atualiza a anterior.
- `Chronos-<versão>-portatil.zip`: sem instalar. Descompacte e abra `Chronos\Chronos.exe`.

O Java vai junto, não precisa instalar nada. No app instalado, o `.env` e o histórico (`chronos.db`) ficam em
`%APPDATA%\Chronos`; na primeira vez, preencha o Jira em **Configurações**. O caminho aparece no topo dessa página.

O desenvolvimento acontece na branch `develop`; a `main` recebe o que vai virar release.

## Rodando

Requisitos: JDK 21 e Maven 3.9+.

```bash
mvn javafx:run   # abre o dashboard
mvn test         # roda os testes
```

A configuração é lida do arquivo `.env` na pasta onde o app é aberto. Veja `.env.example`.
Tudo isso também pode ser editado na tela **Configurações** do app, que grava no mesmo `.env` e aplica na
hora (o caminho do banco vale ao reabrir). Lá também fica a opção de abrir o Chronos ao entrar no Windows
(chave `Run` do usuário no registro; disponível rodando pelo executável instalado).
Embaixo do API token fica a **data de validade** dele (`JIRA_API_TOKEN_EXPIRES`). A Atlassian não informa essa
data pela API, então ela é digitada a partir da lista de tokens em id.atlassian.com. A tela mostra quanto falta
(em amarelo nas duas últimas semanas, em vermelho depois de vencer) e, ao abrir, o app mostra uma notificação se
o token vence em até 14 dias ou já venceu.

O Chronos fica com um ícone na bandeja do Windows (área de notificação, perto do relógio). Fechar a janela
só a esconde e o tempo continua contando; clique no ícone para abrir de novo. Com o botão direito aparecem
as tasks que estão contando, cada uma com **Pausar** e **Finalizar** (move a task para um status concluído
no Jira), além de **Pausar todas** e **Sair**.

A página **Apontamentos** mostra cada task com o tempo total contado, quanto já foi apontado no Jira e o que
falta. Quando a task sai da coluna em andamento ela pausa e aparece como "Falta apontar"; o botão **Apontar**
lança o tempo que falta (em minutos inteiros) no controle de tempo da task no Jira (`/rest/api/3/issue/{key}/worklog`)
e ela passa para "Apontado". O que foi apontado fica gravado no banco; se a task voltar a contar, só o tempo novo
aparece para apontar.
Se o controle de tempo estiver desligado no Jira, ou as tarefas não tiverem o campo "Controle de tempo" (Time
tracking), a página mostra um aviso amarelo no topo: sem esse campo o Jira não aceita apontamento. Em projetos
gerenciados pela equipe o campo conta mesmo que não apareça na tela de edição.

Em **Configurações > Avisos de task** dá para escolher tipos de task (por exemplo "Bug Cliente",
`CHRONOS_ALERT_ISSUE_TYPES`). A cada 30 segundos o Chronos procura tasks desses tipos criadas nos projetos nos
últimos 3 dias, de qualquer responsável. Cada task nova gera uma notificação do Windows, entra na atividade
recente e deixa uma bolinha vermelha no ícone da bandeja e da barra de tarefas até a janela ser aberta. As tasks
já avisadas ficam no banco; ao ligar o aviso (ou trocar os tipos), as que já existiam não geram notificação.

Sem `.env` o app abre com os valores padrão e o Jira aparece como "não configurado".

O histórico é gravado em `chronos.db` (ou no caminho de `CHRONOS_DB_PATH`). Cada intervalo guarda a task,
o início, o fim e o dia em que foi feito; períodos ociosos também ficam gravados. Ao abrir o app, os totais
das tasks e o progresso do dia voltam do banco.

O histórico é separado por Jira (o endereço de `JIRA_BASE_URL`): ao trocar o Jira em Configurações, o painel,
o histórico e os apontamentos passam a mostrar só o que foi contado nesse Jira, e voltar para o anterior traz
o dele de volta. O que foi gravado antes dessa separação continua no banco, mas não aparece em nenhum Jira.

Na página **Histórico** dá para escolher qualquer dia (os dias com tempo gravado aparecem destacados no
calendário) e buscar uma task pela chave ou pelo título em todas as datas, vendo em que dias ela foi feita.

No painel, as listas "Tarefas do projeto" e "Atividade recente" mostram só os primeiros itens (5 tasks e
4 eventos). O botão **Mostrar mais** abre uma janela com a lista completa, 25 itens por página, que continua
atualizando enquanto está aberta. A atividade recente guarda os últimos 200 eventos.

No painel, o botão **+** em "Progresso do dia" adiciona tempo manual numa task (dia, horas e uma nota
opcional). O tempo manual soma no total da task e no progresso do dia, e fica gravado no histórico. Se o
dia passaria de 8h somando o tempo contado e as inserções manuais, a inserção é recusada com o aviso
"Tempo manual inválido".

### Como o app conta o tempo

A cada `POLLING_INTERVAL_SECONDS` o app consulta o Jira Cloud (`/rest/api/3/search/jql`) com e-mail +
[API token](https://id.atlassian.com/manage-profile/security/api-tokens) e lista as suas issues:

- com `JIRA_PROJECT_KEY(S)`: issues dos projetos atribuídas a você que não estão concluídas, mais as
  concluídas hoje;
- com `JIRA_JQL`: a sua consulta, na ordem dela (ou por `updated DESC` se não tiver `ORDER BY`).

Conta tempo toda issue que está numa coluna que conta. As colunas padrão são "Em andamento", "Em progresso" e
"In Progress"; em `JIRA_IN_PROGRESS_STATUSES` dá para acrescentar outras escritas como no quadro (por exemplo
"Test", para quem testa). Com `JIRA_USE_DEFAULT_STATUSES=false`, só as digitadas contam. Com
`CHRONOS_AUTO_START=false`, entrar numa dessas colunas não liga o tempo: ele só conta depois do play, e
continua contando se a task passar para outra coluna que conta. As duas opções também ficam em
Configurações e começam ligadas. Ao mudar de
coluna, por exemplo para "Em análise" ou "Concluído", a task pausa. Várias contam ao mesmo tempo: uma hora trabalhada com duas tasks em andamento soma uma hora em
cada uma. O "tempo hoje" conta o relógio, então essa hora aparece como uma hora só.

O botão de cada task liga ou pausa o tempo dela. Essa escolha vale até a task mudar de coluna no Jira
(ou sair da busca); aí o app volta a seguir o Jira. Sem atividade por `IDLE_THRESHOLD_SECONDS`, todas as tasks pausam e
retomam juntas quando você volta. Se o Jira cair, o app continua com as últimas tasks conhecidas.

## Estado atual

| Fase | O que é | Situação |
|------|---------|----------|
| 1 | Painel (task atual, status, progresso do dia, tarefas, atividade recente) | Feito |
| 2 | Detecção de atividade no Windows | Pendente: hoje o usuário é sempre considerado ativo |
| 3 | Cliente do Jira | Feito: lista as suas issues e conta tempo nas que estão em andamento |
| 4 | Time tracking | Feito: um cronômetro por task, várias em paralelo, play/pausa manual |
| 5 | Persistência em SQLite | Feito: intervalos e ociosidade gravados em `chronos.db` e restaurados ao abrir |
| 6 | Robustez (offline, logs, credenciais) | Parcial: queda do Jira mantém a última task |
| 7 | Empacotamento `.exe` com jpackage | Feito: instalador e versão portátil gerados pelo GitHub Actions a cada merge na `main` |

## Estrutura

```text
com.chronos.tracker
├── ChronosApp / Launcher   entrada do JavaFX
├── ui                      janela principal, painel e estilos (app.css)
├── tracking                MultiTaskTracker (um cronômetro por issue) e TrackingEngine (junta tudo)
├── activity                estados ATIVO / POSSIVELMENTE IDLE / INATIVO
├── jira                    interface do serviço do Jira
├── persistence             histórico em SQLite (SqliteHistoryStore)
└── config                  leitura do .env
```

O polling do Jira e o monitoramento rodam em threads de fundo; a UI só é atualizada via
`Platform.runLater`, então ela nunca trava esperando o Jira.
