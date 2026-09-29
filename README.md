# PayFlow

A payment gateway backend (Java, Spring Boot, PostgreSQL) built to stay correct under concurrent requests, network retries and partial failures.

> Work in progress. The full design is in [docs/SPEC.md](docs/SPEC.md).

## Run locally

Requires Java 25 and Docker.

```bash
docker compose up -d          # start Postgres
cd payflow-api
./mvnw spring-boot:run        # start the API on http://localhost:8080
```

Check it's up: `GET http://localhost:8080/health` → `{"status":"UP","database":"UP"}`

Run tests (they start their own throwaway Postgres via Testcontainers):

```bash
cd payflow-api
./mvnw verify
```
