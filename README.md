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

The secret files, and the services that mount them:

| File in `./secrets/` | Mounted by |
|---|---|
| `spring.datasource.password` | postgres, api |
| `aulaflix.bff.key` | api, web |
| `aulaflix.storage.read-only.access-key-id`, `aulaflix.storage.read-only.secret-access-key` | storage-init, api |
| `aulaflix.storage.read-write.access-key-id`, `aulaflix.storage.read-write.secret-access-key` | storage-init, api |
| `storage.root-user`, `storage.root-password` | storage, storage-init |
| `minio.license` | storage |

Compose mounts each file as it is on the host, with its owner and mode, and the API's image runs as the unprivileged
user 10001, so a file the `api` service mounts must be readable by that user: `chmod 644 secrets/*` locally, which is
what the commands above already give under the usual `umask 022`. No secret goes in an environment variable, so none
shows in `docker inspect`.

## Creating an Admin

No HTTP endpoint creates an Admin. The jar does, when `admin` is its first argument, and so does the API's image,
which runs the jar:

```shell
./mvnw -DskipTests package
java -jar target/aulaflix-api-0.0.1-SNAPSHOT.jar admin create --email you@example.com --name "Your Name"
# or, in the image, against the Compose stack's database
docker compose run --rm api admin create --email you@example.com --name "Your Name"
```

It asks for the password twice without echoing it, so it needs a terminal: `docker compose run` allocates one by
default, and with `-T`, or from a script, the command refuses and creates nothing. It starts no web server and no scheduled
job, and it never migrates: while a migration is pending it refuses, so start the API once first. Like every new
password, it must not be one HIBP has seen in a breach (see [Student accounts](#student-accounts)).

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
catalog from then on, with its Planned topics. `{ "status": "ON_SALE" }` launches it, from Draft or from Coming soon:
its price and Syllabus show instead, and it can be bought. A Course moves forward only, and sending the state it is
already in changes nothing. A move it isn't ready for is refused with every missing field listed. Once it leaves
Draft, its slug is frozen, it can no longer be deleted, and a `PUT` that would leave it without a field its state
needs is refused the same way.

Going On sale needs `priceCents`, which `maxInstallments` must divide exactly, and `freeLessonId`: the Free lesson,
which anyone may watch, a published Lesson of the Course from any Module. While On sale, the price and the Free lesson
can change, but never be cleared.

## Shaping a Course's outline

The outline is a Course's Modules in order, each with its Lessons in order; a Lesson's number follows from it.
`POST /v1/admin/courses/{courseId}/modules` `{ "title": … }` appends a Module, and
`POST /v1/admin/modules/{moduleId}/lessons` `{ "title": …, "slug": … }` appends an unpublished Lesson to a Module. A
Lesson's slug is unique within its Course. `PUT /v1/admin/modules/{moduleId}` renames a Module and
`PUT /v1/admin/lessons/{lessonId}` edits a Lesson; `DELETE` at either address removes an empty Module or an
unpublished Lesson.

`GET /v1/admin/courses/{courseId}/outline` reads the order as `[{ "moduleId": …, "lessonIds": [ … ] }, …]`. Edit it
and `PUT` it back to reorder the Modules and move Lessons between them in one change. It must name exactly the
Course's current Modules and Lessons, each once, or nothing changes.

`PUT /v1/admin/lessons/{lessonId}/status` `{ "status": "PUBLISHED" }` publishes a Lesson once its video is linked;
sending it again changes nothing. Until then the Lesson shows as "Em breve" in the Syllabus. A published Lesson is
never unpublished nor deleted, and its slug is frozen, but its title can change and its video can be replaced.

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
a slug no Course has. An On sale Course comes with its `pricing`, Pix price and installment worked out, and its page
with the Syllabus: the Modules that have Lessons, numbered, and every Lesson numbered across them, "Em breve" ones
included, with no video URL anywhere. Every request but the Admin's carries `AulaFlix-BFF-Key`, without which the
API answers 403, and then `AulaFlix-Client-IP`, the browser's IP address, without which it answers 400. The OpenAPI
document of these endpoints is at `/v3/api-docs/bff`.

```shell
curl -H "AulaFlix-BFF-Key: $(cat secrets/aulaflix.bff.key)" -H "AulaFlix-Client-IP: 127.0.0.1" \
    http://localhost:8080/v1/courses
```

## Playing a Lesson

`GET /v1/lessons/{lessonId}/playback` answers `{ url, expiresAt }`: a presigned `GET` of the Lesson's video, signed
with the read-only key on every call, valid for 4 hours (`aulaflix.storage.playback-url-lifetime`), and answered with
`Cache-Control: no-store`. Only a published Lesson of an On sale Course plays; any other answers 404
`lesson-not-found`, an id of any shape included. The Free lesson plays for anyone, without a session, and any other
Lesson answers 401 without one. The session is optional, but a token that is sent must be valid, even for the Free
lesson, and an Admin's gets a 403: the Admin previews through their own endpoint. Without a session, one IP gets 30
plays an hour, whatever they answer (`aulaflix.rate-limits.visitor-playback.*`), on top of the 600 requests a minute
every BFF request counts against.

## Student accounts

The web's `/entrar` asks for the email first: `POST /v1/account-lookups` `{ email }` answers `{ exists }`, an Admin's
email included (ADR 0005). Then either `POST /v1/sessions` `{ email, password }` signs the Student in, or
`POST /v1/accounts` `{ name, email, password }` creates the Account and signs them in at once; both answer 201
`{ token, expiresAt }`. The BFF sends the token as `Authorization: Bearer` to `GET /v1/account`, which answers
`{ name, email, emailConfirmed }`, to `PUT /v1/account` `{ name }`, and to `DELETE /v1/sessions/current`, which ends that
session only. A Student's session ends after 7 days without use, or 30 days after sign-in. These flows never serve an
Admin: an Admin's email is taken at sign-up and fails sign-in like a wrong password, and an Admin's token gets 403.

A new password, a Student's or an Admin's, has 8 characters to 72 bytes in UTF-8, and must not be one HIBP has seen in
a breach (`breached`). Only the first 5 hex digits of its SHA-1 go to `aulaflix.hibp.base-url`; when HIBP fails or
takes longer than `aulaflix.hibp.timeout`, the password is taken unchecked and a WARN says so. 10 failed sign-ins for
an email within 15 minutes block it for 15 minutes, with a counter apart from the Admins'. Per IP, the email look-up
and sign-in together get 60 requests an hour, and sign-up 10 a day, whatever they answer
(`aulaflix.rate-limits.look-ups-and-sign-ins.*`, `aulaflix.rate-limits.sign-ups.*`).

## The API's image and the `full` profile

The `Dockerfile` builds the API's image: the jar on a JRE, run as the unprivileged user 10001, with the heap at 75% of
the container's memory limit. `docker stop` ends it gracefully: the JVM gets the SIGTERM, Spring Boot lets the
requests in flight finish, for 30 seconds at most, and Compose waits 40 before it kills.

The `full` profile adds the API's image, built from this repository, and the web's, from `aulaflix-web`'s private
image on GHCR, so the Admin rehearses Course JSON files against the whole stack before production. The API waits for
PostgreSQL and the storage to be healthy and for `storage-init` to complete; it reaches the storage at
`storage:9000`, but signs URLs for `localhost:9000`, where curl and the browser reach it.

```shell
cp .env.example .env              # the web image's tag; nothing secret
docker login ghcr.io              # with a GitHub token that has read:packages
docker compose --profile full up -d --build
```

The API answers on `127.0.0.1:8080` and the web on `http://localhost:3001`. The web takes its config as `NUXT_*` variables: `NUXT_API_BASE_URL`, the Turnstile site key,
and `NUXT_BFF_KEY`, which the `web` service reads from `secrets/aulaflix.bff.key` as Nuxt's server starts, so it stays
out of the Compose file and of `docker inspect`. The API applies its migrations as it starts; then create the Admin:

```shell
docker compose run --rm api admin create --email you@example.com --name "Your Name"
```

### Rehearsing a Course

The Course's document is a JSON file (see [Authoring a Course](#authoring-a-course)), say `course.json`, with every
field but `freeLessonId`, which needs the Lesson first:

```json
{
  "slug": "git-do-zero",
  "title": "Git do zero",
  "summary": "Versione seu código com Git, do primeiro commit ao pull request.",
  "area": "DEVOPS", "icon": "CONTAINER", "tone": "SAGE",
  "about": ["Um curso prático sobre o Git do dia a dia."],
  "learn": ["Criar commits", "Trabalhar com branches"],
  "audience": ["Quem está começando a programar"],
  "plannedTopics": ["Commits", "Branches"],
  "faq": [{ "question": "Preciso instalar algo?", "answer": "Só o Git." }],
  "priceCents": 19700, "pixDiscountPercent": 10, "maxInstallments": 1
}
```

Then, with `curl` and `jq`:

```shell
API=http://localhost:8080
read -rsp 'Password: ' PASSWORD; echo
TOKEN=$(jq -n --arg email you@example.com --arg password "$PASSWORD" '{$email, $password}' \
    | curl -sf -H 'Content-Type: application/json' -d @- $API/v1/admin/sessions | jq -r .token)
auth=(-H "Authorization: Bearer $TOKEN")
json=(-H 'Content-Type: application/json')

# 1. The Draft, then its whole document from the file; readiness lists what each next state still misses
COURSE_ID=$(jq '{slug, title}' course.json | curl -sf "${auth[@]}" "${json[@]}" -d @- $API/v1/admin/courses | jq .id)
curl -sf -X PUT "${auth[@]}" "${json[@]}" -d @course.json $API/v1/admin/courses/$COURSE_ID | jq .readiness

# 2. A Module and a Lesson
MODULE_ID=$(curl -sf "${auth[@]}" "${json[@]}" -d '{"title": "Primeiros passos"}' \
    $API/v1/admin/courses/$COURSE_ID/modules | jq .id)
LESSON_ID=$(curl -sf "${auth[@]}" "${json[@]}" -d '{"title": "O primeiro commit", "slug": "o-primeiro-commit"}' \
    $API/v1/admin/modules/$MODULE_ID/lessons | jq .id)

# 3. The video: upload it straight to the storage, link it, and publish the Lesson
UPLOAD=$(curl -sf -X POST "${auth[@]}" $API/v1/admin/lessons/$LESSON_ID/video-uploads)
curl -sf --upload-file aula.mp4 -H 'Content-Type: video/mp4' "$(jq -r .uploadUrl <<<"$UPLOAD")"
jq '{objectKey}' <<<"$UPLOAD" | curl -sf -X PUT "${auth[@]}" "${json[@]}" -d @- $API/v1/admin/lessons/$LESSON_ID/video
curl -sf -X PUT "${auth[@]}" "${json[@]}" -d '{"status": "PUBLISHED"}' $API/v1/admin/lessons/$LESSON_ID/status

# 4. The Free lesson, then On sale
jq --argjson lesson $LESSON_ID '.freeLessonId = $lesson' course.json \
    | curl -sf -X PUT "${auth[@]}" "${json[@]}" -d @- $API/v1/admin/courses/$COURSE_ID | jq .readiness
curl -sf -X PUT "${auth[@]}" "${json[@]}" -d '{"status": "ON_SALE"}' $API/v1/admin/courses/$COURSE_ID/status
```

The Course now shows at `http://localhost:3001/cursos/git-do-zero`, with its price, its Syllabus and the Free lesson,
which plays. When the API refuses the file, `curl -sf` hides why: drop the `f` to see the `ProblemDetail`, fix the
file and `PUT` it again. `docker compose --profile full down -v` throws the rehearsal away, volumes included.

## Tests

`./mvnw test` needs Docker: PostgreSQL and AIStor Free run in Testcontainers, AIStor with the license from
`secrets/minio.license`, which CI writes there from a secret. HIBP is played by WireMock, in the tests' JVM. `./mvnw verify` also runs the `*IT` tests, which make the
signed uploads and playback requests over real HTTP. Mutation testing runs with
`./mvnw test-compile org.pitest:pitest-maven:mutationCoverage`, and its report lands in `target/pit-reports/`. It
mutates the `command`, `config`, `exception` and `service` packages. To check one slice, name the classes it changed:

```shell
./mvnw test-compile org.pitest:pitest-maven:mutationCoverage \
    -DtargetClasses=com.devlabs.aulaflix.service.OutlineService,com.devlabs.aulaflix.exception.ProblemHandler
```

PIT tests each mutated class in a JVM of its own, which boots the application and its own PostgreSQL container, and
runs four at once; `-Dthreads=` changes that.
