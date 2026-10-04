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

## Authoring a Course

A Course starts as a Draft that only Admins see: `POST /v1/admin/courses` `{ "slug": …, "title": … }`. Read its
document with `GET /v1/admin/courses/{courseId}`, edit the JSON, and `PUT` it back whole; a field left out is
cleared, and what only reads show (`id`, `status`, `readiness`) may stay in the body. `readiness` lists, for each
state the Course can move to next, the fields still missing. `DELETE` removes a Draft, and only a Draft, with its
Modules and Lessons.

## Shaping a Course's outline

The outline is a Course's Modules in order, each with its Lessons in order; a Lesson's number follows from it.
`POST /v1/admin/courses/{courseId}/modules` `{ "title": … }` appends a Module, and
`POST /v1/admin/modules/{moduleId}/lessons` `{ "title": …, "slug": … }` appends an unpublished Lesson to a Module. A
Lesson's slug is unique within its Course. `PUT /v1/admin/modules/{moduleId}` renames a Module and
`PUT /v1/admin/lessons/{lessonId}` edits a Lesson; `DELETE` at either address removes an empty Module or a Lesson.

`GET /v1/admin/courses/{courseId}/outline` reads the order as `[{ "moduleId": …, "lessonIds": [ … ] }, …]`. Edit it
and `PUT` it back to reorder the Modules and move Lessons between them in one change. It must name exactly the
Course's current Modules and Lessons, each once, or nothing changes.

## Tests

`./mvnw test` needs Docker: PostgreSQL runs in Testcontainers. Mutation testing runs with
`./mvnw test-compile org.pitest:pitest-maven:mutationCoverage`, and its report lands in `target/pit-reports/`. It
mutates the `command`, `config`, `exception` and `service` packages. To check one slice, name the classes it changed:

```shell
./mvnw test-compile org.pitest:pitest-maven:mutationCoverage \
    -DtargetClasses=com.devlabs.aulaflix.service.OutlineService,com.devlabs.aulaflix.exception.ProblemHandler
```

PIT tests each mutated class in a JVM of its own, which boots the application and its own PostgreSQL container, and
runs four at once; `-Dthreads=` changes that.
