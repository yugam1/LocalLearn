# 🌐 HTTP Request Collections

Runnable `.http` files, organised the same way as `docs/` — one file per learning task —
so you can hit the API while you read the doc that explains it.

## Setup (once)

1. Install the REST Client extension:
   ```bash
   code --install-extension humao.rest-client
   ```
2. Start the infrastructure and the app:
   ```bash
   # Postgres on 5432, Kafka on 9092 (Kafka only matters from Phase 2 onwards)
   cd order-service && ./mvnw spring-boot:run
   ```
3. Open any `.http` file and click the **Send Request** link that appears above a request.

## Environments

`{{host}}` and friends resolve from `.vscode/settings.json` → `rest-client.environmentVariables`.
Pick an environment with the status-bar item at the bottom right of VS Code
(or `Cmd+Shift+P` → *Rest Client: Switch Environment*). Default is `local`.

## Layout

| Folder | Docs it mirrors | Contents |
|---|---|---|
| `phase1/` | `docs/phase1_task*.md` | Core foundation — REST, validation, JPA, queries, transactions, N+1, logging |

Later phases get their own folder as we go.

### Phase 1

| File | Task | What you're exercising |
|---|---|---|
| `phase1/task1-rest-basics.http` | 1 | CRUD verbs, status codes, `Location`-style response shape |
| `phase1/task2-validation-exceptions.http` | 2 | `@Valid` failures, 404 / 400 / 422 / 500 from `GlobalExceptionHandler` |
| `phase1/task3-jpa-relationships.http` | 3 | `@OneToMany` cascade, orphan removal, server-computed `totalAmount` |
| `phase1/task4-queries-projections.http` | 4 | Specifications, pagination + sorting, interface vs DTO projections |
| `phase1/task5-transactions-locking.http` | 5 | Optimistic lock 409, NESTED savepoints via batch, rollback behaviour |
| `phase1/task6-n1-hibernate-stats.http` | 6 | Hibernate statistics before/after, proving `@BatchSize` works |
| `phase1/task7-logging-mdc.http` | 7 | `X-Correlation-ID` propagation through MDC into the logs |

## Conventions used in these files

- `###` separates requests. Everything between two `###` lines is one request.
- `# @name foo` names a request so later ones can chain off it:
  `{{foo.response.body.$.id}}`.
- Each request has an `# Expect:` comment stating the status code you should get.
  If you get something else, that's the bug — or the lesson.
