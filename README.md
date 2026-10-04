# aulaflix-api

The AulaFlix REST API: the system of record for Accounts, the catalog, Orders, Enrollments, Progress and Waitlists.
The browser never calls it; the Nuxt server of `aulaflix-web` does ([ADR 0001](docs/adr/0001-the-nuxt-server-is-the-only-api-client.md)).

## Running locally

Secrets are files in `./secrets/` (git-ignored), each named after the property it sets. The containers see them at
`/run/secrets/`, and the API reads either folder through `spring.config.import=optional:configtree:…`.

```shell
mkdir -p secrets
openssl rand -base64 24 > secrets/spring.datasource.password

docker compose up -d     # PostgreSQL on 127.0.0.1:5432
./mvnw spring-boot:run   # or run AulaflixApiApplication from the IDE, from the repository root
```

The API applies its Flyway migrations when it starts.

## Creating an Admin

No HTTP endpoint creates an Admin. The jar does, when `admin` is its first argument:

```shell
./mvnw -DskipTests package
java -jar target/aulaflix-api-0.0.1-SNAPSHOT.jar admin create --email you@example.com --name "Your Name"
```

It asks for the password twice without echoing it, so it needs a terminal. It starts no web server and no scheduled
job, and it never migrates: while a migration is pending it refuses, so start the API once first.

`admin password --email you@example.com` sets a new password the same way and ends every session of that Admin. It is
the Admins' only reset: no HTTP endpoint creates an Admin or changes an Admin's password.

## Signing in as an Admin

Admins work through Swagger UI or curl, over the SSH tunnel. Swagger UI is at `/swagger-ui.html` and the OpenAPI
document at `/v3/api-docs/admin`. Sign in with `POST /v1/admin/sessions` `{ "email": …, "password": … }`, then paste
the returned `token` into "Authorize". A session ends after 30 minutes without use, 8 hours after sign-in at most, or
with `DELETE /v1/admin/sessions/current`.

## Tests

`./mvnw test` needs Docker: PostgreSQL runs in Testcontainers. Mutation testing runs with
`./mvnw test-compile org.pitest:pitest-maven:mutationCoverage`, and its report lands in `target/pit-reports/`.
