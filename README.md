# aulaflix-api

The AulaFlix REST API: the system of record for Accounts, the catalog, Orders, Enrollments, Progress and Waitlists.
The browser never calls it; the Nuxt server of `aulaflix-web` does ([ADR 0001](docs/adr/0001-the-nuxt-server-is-the-only-api-client.md)).

## Running locally

Secrets are files in `./secrets/` (git-ignored), each named after the property it sets. The containers see them at
`/run/secrets/`, and the API reads either folder through `spring.config.import=optional:configtree:…`.

```shell
mkdir -p secrets
openssl rand -base64 24 > secrets/spring.datasource.password
openssl rand -base64 32 > secrets/aulaflix.bff.key   # the web's server sends the same key
# The storage: its root, and the API's two keys, which storage-init creates. Hex, since a key ID goes into URLs.
openssl rand -hex 10 > secrets/storage.root-user
openssl rand -hex 24 > secrets/storage.root-password
for key in read-only read-write; do
    openssl rand -hex 10 > secrets/aulaflix.storage.$key.access-key-id
    openssl rand -hex 20 > secrets/aulaflix.storage.$key.secret-access-key
done
# …and AIStor Free's license, downloaded from your MinIO account, as secrets/minio.license

docker compose up -d     # PostgreSQL on 127.0.0.1:5432, AIStor Free's S3 API on 127.0.0.1:9000
./mvnw spring-boot:run   # or run AulaflixApiApplication from the IDE, from the repository root
```

The API applies its Flyway migrations when it starts. It refuses to start without a BFF key of at least 32
characters, or without the storage's two keys.

AIStor Free answers every S3 request with a denial until it has its license, which the same file serves locally, in
the tests and in production. On every `up`, `storage-init` creates the private `videos` bucket and the API's two
users: a read-only one, which signs playback, and a read-write one, which signs uploads and serves the API's own reads
and deletes. Running it again changes nothing. Only `storage` and `storage-init` hold the root credentials, and the
console is not published.

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

`PUT /v1/admin/courses/{courseId}/status` `{ "status": "COMING_SOON" }` announces the Course: it shows in the public
catalog from then on, with its Planned topics. A Course moves forward only, and sending the state it is already in
changes nothing. A move it isn't ready for is refused with every missing field listed. Once it leaves Draft, its slug
is frozen, it can no longer be deleted, and a `PUT` that would leave it without a field its state needs is refused
the same way.

## Shaping a Course's outline

The outline is a Course's Modules in order, each with its Lessons in order; a Lesson's number follows from it.
`POST /v1/admin/courses/{courseId}/modules` `{ "title": … }` appends a Module, and
`POST /v1/admin/modules/{moduleId}/lessons` `{ "title": …, "slug": … }` appends an unpublished Lesson to a Module. A
Lesson's slug is unique within its Course. `PUT /v1/admin/modules/{moduleId}` renames a Module and
`PUT /v1/admin/lessons/{lessonId}` edits a Lesson; `DELETE` at either address removes an empty Module or a Lesson.

`GET /v1/admin/courses/{courseId}/outline` reads the order as `[{ "moduleId": …, "lessonIds": [ … ] }, …]`. Edit it
and `PUT` it back to reorder the Modules and move Lessons between them in one change. It must name exactly the
Course's current Modules and Lessons, each once, or nothing changes.

## Uploading a Lesson's video

A Lesson's video is one faststart H.264/AAC MP4, encoded before the upload ([ADR
0007](docs/adr/0007-lesson-videos-are-progressive-mp4-encoded-before-upload.md)), and its bytes never pass through
the API:

```shell
ffmpeg -i raw.mov -c:v libx264 -c:a aac -movflags +faststart aula.mp4

# 1. An upload URL, valid for an hour, for a new key under the Lesson's prefix
curl -X POST -H "Authorization: Bearer $TOKEN" http://localhost:8080/v1/admin/lessons/21/video-uploads
# 2. The upload, straight to the storage, with the required headers: one PUT, which the edge caps at 5 GiB
curl --upload-file aula.mp4 -H 'Content-Type: video/mp4' "$uploadUrl"
# 3. The link, which reads the duration from the file's header
curl -X PUT -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d '{"objectKey": "lessons/21/…mp4"}' http://localhost:8080/v1/admin/lessons/21/video
```

Linking reads the file's header, never its media, and refuses anything but a faststart H.264/AAC MP4 that lasts a
second once rounded, with a 409 naming the first problem: `video-not-mp4` (a QuickTime movie included),
`video-not-faststart` (a fragmented MP4 included), `video-not-h264` (`avc1` or `avc3`), `audio-not-aac` (no audio at
all is fine) or `video-too-short`. A refused link changes nothing. Linking a new upload replaces the Lesson's video
and deletes every other object under its prefix, abandoned uploads included; there is no unlink. Linking never
publishes. `GET /v1/admin/lessons/{lessonId}/playback` answers a URL that plays the linked video for 4 hours, in any
state, so the Admin checks it first. Deleting a Lesson, or a Draft, deletes its videos too.

## The public catalog

The BFF reads the catalog with `GET /v1/courses` and `GET /v1/courses/{slug}`, without a session. A Draft answers like
a slug no Course has. Every request but the Admin's carries `AulaFlix-BFF-Key`, without which the API answers 403,
and then `AulaFlix-Client-IP`, the browser's IP address, without which it answers 400. The OpenAPI document of these
endpoints is at `/v3/api-docs/bff`.

```shell
curl -H "AulaFlix-BFF-Key: $(cat secrets/aulaflix.bff.key)" -H "AulaFlix-Client-IP: 127.0.0.1" \
    http://localhost:8080/v1/courses
```

## Tests

`./mvnw test` needs Docker: PostgreSQL and AIStor Free run in Testcontainers, AIStor with the license from
`secrets/minio.license`, which CI writes there from a secret. `./mvnw verify` also runs the `*IT` tests, which make the
signed uploads and playback requests over real HTTP. Mutation testing runs with
`./mvnw test-compile org.pitest:pitest-maven:mutationCoverage`, and its report lands in `target/pit-reports/`. It
mutates the `command`, `config`, `exception` and `service` packages. To check one slice, name the classes it changed:

```shell
./mvnw test-compile org.pitest:pitest-maven:mutationCoverage \
    -DtargetClasses=com.devlabs.aulaflix.service.OutlineService,com.devlabs.aulaflix.exception.ProblemHandler
```

PIT tests each mutated class in a JVM of its own, which boots the application and its own PostgreSQL container, and
runs four at once; `-Dthreads=` changes that.
