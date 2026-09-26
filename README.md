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

### Como o app escolhe a task atual

A cada `POLLING_INTERVAL_SECONDS` o app consulta o Jira Cloud (`/rest/api/3/search/jql`) com e-mail +
[API token](https://id.atlassian.com/manage-profile/security/api-tokens) e usa a primeira issue do resultado:

- com `JIRA_PROJECT_KEY(S)`: issues dos projetos, atribuídas a você, com status na categoria
  "Em andamento", da atualizada mais recentemente para a mais antiga;
- com `JIRA_JQL`: a sua consulta, na ordem dela (ou por `updated DESC` se não tiver `ORDER BY`).

Se nenhuma issue vier, o tracking para. A task manual digitada no dashboard tem prioridade sobre o Jira.
Se o Jira cair, o app continua contando na última task conhecida.

## Estado atual

| Fase | O que é | Situação |
|------|---------|----------|
| 1 | Dashboard (status, task, tempo, Jira) | Feito |
| 2 | Detecção de atividade no Windows | Pendente: hoje o usuário é sempre considerado ativo |
| 3 | Cliente do Jira | Feito: busca a task em andamento atribuída a você (ou a sua `JIRA_JQL`) |
| 4 | Time tracking (start/pause/resume/stop) | Feito, com testes |
| 5 | Persistência em SQLite | Pendente: o histórico fica só em memória |
| 6 | Robustez (offline, logs, credenciais) | Parcial: queda do Jira mantém a última task |
| 7 | Empacotamento `.exe` com jpackage | Pendente |

## Estrutura

```text
com.chronos.tracker
├── ChronosApp / Launcher   entrada do JavaFX
├── ui                      dashboard
├── tracking                TimeTracker (cronômetro por issue) e TrackingEngine (junta tudo)
├── activity                estados ATIVO / POSSIVELMENTE IDLE / INATIVO
├── jira                    interface do serviço do Jira
└── config                  leitura do .env
```

O polling do Jira e o monitoramento rodam em threads de fundo; a UI só é atualizada via
`Platform.runLater`, então ela nunca trava esperando o Jira.
