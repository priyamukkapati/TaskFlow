# TaskFlow

A Java 17 work-management demo with a browser Kanban board, HTTP API, comments, local persistence, and optimistic version checks.

This is a single-process local application. The browser is plain JavaScript and HTML; the server uses the JDK HTTP server. It does not implement Spring Boot, Kotlin, React, PostgreSQL, Redis, JWT, RAG, microservices, or AWS EKS. Those are possible future work, not current achievements.

## Run

Install a JDK 17 or newer. No external libraries or build-tool downloads are required.

```sh
git clone https://github.com/priyamukkapati/TaskFlow.git
cd TaskFlow
sh run.sh test
sh run.sh demo
sh run.sh serve
```

Open http://127.0.0.1:8080. Add a task, move it from TODO to IN PROGRESS to DONE, and add a comment. Stop and restart the server: the board reloads from `data/tasks.tsv`. Use `PORT=8081 sh run.sh serve` to select another port.

## API

POST bodies use `application/x-www-form-urlencoded`; responses use JSON.

| Method | Endpoint | Fields | Result |
| --- | --- | --- | --- |
| GET | `/health` | none | 200 health status |
| GET | `/api/tasks` | none | 200 task list |
| POST | `/api/tasks` | `title`, `assignee` | 201 new task at version 1 |
| POST | `/api/tasks/{id}/move` | `version`, `status` | 200 updated task |
| POST | `/api/tasks/{id}/comments` | `version`, `text` | 200 updated task |

```sh
curl -i -d 'title=Review+API&assignee=Priya' http://127.0.0.1:8080/api/tasks
curl http://127.0.0.1:8080/api/tasks
```

Copy the returned ID and version into a move request. A stale version returns 409; invalid input returns 400; a missing task returns 404. Moves are allowed only between adjacent columns. Tasks have up to 100 comments and POST bodies are limited to 8 KiB. Assignee names are labels; they do not prove identity or grant permissions.

## Reliability proof

`sh run.sh demo` submits 100 simultaneous comments against the same initial version using eight threads. It asserts one accepted update, 99 conflicts, and persisted version 2 after reopening the store.

```text
attempts=100 accepted=1 conflicts=99 elapsed_ms=10.75 persisted_version=2
```

Recorded locally on 2026-10-14 using OpenJDK 17.0.20. Timing varies by machine and includes executor overhead and one snapshot write. This is a store concurrency demo, not an HTTP throughput benchmark, cloud deployment, or evidence of 600 requests/minute. No cache comparison was performed.

`sh run.sh test` currently passes 11 checks covering restart recovery, stale-version rejection, racing updates, invalid input, storage-write rollback, HTTP errors, and browser-page serving. GitHub Actions is configured to run tests and the invariant demo.

## Design choices and limitations

Immutable task records and synchronized writes prevent lost updates within one store instance. Each change writes a new snapshot and replaces the old file before exposing the new state in memory. If atomic rename is unsupported, replacement falls back to a regular filesystem move. No fsync, database transaction, multi-process lock, or power-loss guarantee is provided.

All tasks share one board. Comments refresh after local actions; there is no WebSocket delivery, login, tenant isolation, due-date assistant, or external service. Snapshot work is O(number of tasks) per write. The server binds to loopback and is intended for local development. Do not expose it publicly as an authenticated service.

## Next proof

Move the HTTP adapter to Spring Boot while retaining the store-level tests, then add PostgreSQL with a conditional version update in a database transaction. Demonstrate two independent clients racing against the same task. Add authenticated project membership before adding deployment or AI features.

## Resume description

Java work-management prototype with a browser Kanban board, task and comment HTTP endpoints, persistent local snapshots, and optimistic version checks; demonstrated one successful update and 99 conflicts in a 100-attempt concurrent-write test.
