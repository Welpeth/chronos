# Chronos — Jira Work Tracker

Aplicativo desktop (Windows) em Java 21 + JavaFX que acompanha sua atividade e registra
automaticamente o tempo gasto em cada issue do Jira.

## Rodando

Requisitos: JDK 21 e Maven 3.9+.

```bash
mvn javafx:run   # abre o dashboard
mvn test         # roda os testes
```

A configuração é lida do arquivo `.env` na pasta onde o app é aberto. Veja `.env.example`.
Sem `.env` o app abre com os valores padrão e o Jira aparece como "não configurado".

O histórico é gravado em `chronos.db` (ou no caminho de `CHRONOS_DB_PATH`). Cada intervalo guarda a task,
o início, o fim e o dia em que foi feito; períodos ociosos também ficam gravados. Ao abrir o app, os totais
das tasks e o progresso do dia voltam do banco.

Na página **Histórico** dá para escolher qualquer dia (os dias com tempo gravado aparecem destacados no
calendário) e buscar uma task pela chave ou pelo título em todas as datas, vendo em que dias ela foi feita.

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

Conta tempo toda issue que está na coluna "Em andamento" do quadro (as colunas que contam ficam em
`JIRA_IN_PROGRESS_STATUSES`; o padrão é "Em andamento", "Em progresso" e "In Progress"). Ao mudar de
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
| 7 | Empacotamento `.exe` com jpackage | Pendente |

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
