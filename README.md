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
openssl rand -base64 32 > secrets/aulaflix.codes.hmac-key   # the 6-digit codes are stored as HMACs under it
# The storage: its root, and the API's two keys, which storage-init creates. Hex, since a key ID goes into URLs.
openssl rand -hex 10 > secrets/storage.root-user
openssl rand -hex 24 > secrets/storage.root-password
for key in read-only read-write; do
    openssl rand -hex 10 > secrets/aulaflix.storage.$key.access-key-id
    openssl rand -hex 20 > secrets/aulaflix.storage.$key.secret-access-key
done
# …and AIStor Free's license, downloaded from your MinIO account, as secrets/minio.license
# …and an API key of your Asaas sandbox account (Integrações > Chaves de API), as secrets/aulaflix.asaas.api-key
# The token Asaas sends with each webhook delivery: 32 to 255 characters, registered with the sandbox's webhook
openssl rand -hex 32 > secrets/aulaflix.asaas.webhook-token
# Cloudflare Turnstile's test secret, which passes every token, to match the web's test site key
echo 1x0000000000000000000000000000000AA > secrets/aulaflix.turnstile.secret-key

docker compose up -d     # PostgreSQL on 127.0.0.1:5432, AIStor Free's S3 API on 127.0.0.1:9000, Mailpit (below)
./mvnw spring-boot:run   # or run AulaflixApiApplication from the IDE, from the repository root
```

The API applies its Flyway migrations when it starts. It refuses to start without a BFF key or a codes HMAC key of at
least 32 characters, without the storage's two keys, without the Asaas key and webhook token, or without the Turnstile
secret.

AIStor Free answers every S3 request with a denial until it has its license, which the same file serves locally, in
the tests and in production. On every `up`, `storage-init` creates the private `videos` bucket and the API's two
users: a read-only one, which signs playback, and a read-write one, which signs uploads and serves the API's own reads
and deletes. Running it again changes nothing. Only `storage` and `storage-init` hold the root credentials, and the
console is not published.

Mailpit catches every email the API sends locally: its SMTP server listens on 127.0.0.1:1025, without TLS, which is
where the default `spring.mail.*` points, and its inbox is at <http://localhost:8025>.

The secret files, and the services that mount them:

| File in `./secrets/` | Mounted by |
|---|---|
| `spring.datasource.password` | postgres, api |
| `aulaflix.bff.key` | api, web |
| `aulaflix.codes.hmac-key` | api |
| `aulaflix.storage.read-only.access-key-id`, `aulaflix.storage.read-only.secret-access-key` | storage-init, api |
| `aulaflix.storage.read-write.access-key-id`, `aulaflix.storage.read-write.secret-access-key` | storage-init, api |
| `storage.root-user`, `storage.root-password` | storage, storage-init |
| `minio.license` | storage |
| `aulaflix.asaas.api-key`, `aulaflix.asaas.webhook-token` | api |
| `aulaflix.turnstile.secret-key` | api |

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

## Granting Enrollments by hand

`POST /v1/admin/enrollments` `{ "email": …, "courseId": …, "note": … }` gives a Student every published Lesson of a
Course, for example after a chargeback won in the Asaas UI, or as a courtesy. The note, up to 500 characters, is
required: it is the only record of why. The email must be a Student's: one with no Account, or an Admin's, gets 409
`student-account-required`, and the person signs up first. A Draft or unknown Course gets 409
`course-not-enrollable`, and a Student who already has an active Enrollment in the Course gets 409
`already-enrolled`. A Coming soon Course takes Enrollments too; its Lessons play from the launch. No email is sent:
tell the Student.

`PUT /v1/admin/enrollments/{id}/status` `{ "status": "ENDED", "note": … }` ends it, and the Student loses every Lesson
but the Free one. The ending is final: the Enrollment stays, ended, and access comes back only through a new grant.
`GET /v1/admin/enrollments` lists every Enrollment, newest first, with who granted it and why, and how it ended: 20 to
a page by default (`page`, from 0, and `size`, at most 100), filtered by any of `email`, `courseId` and
`active=true|false`. `GET /v1/admin/enrollments/{id}` reads one.

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
Lesson answers 401 without one, and 409 `enrollment-required` to a Student without an active Enrollment in its
Course. The session is optional, but a token that is sent must be valid, even for the Free
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

Sign-up queues the confirmation link, which is also the welcome email: `{webBase}/confirmar-email#<token>`, where
`webBase` is `aulaflix.web.base-url`. The web's page posts the token to `POST /v1/email-confirmations` `{ token }`,
without a session, which answers 204 and signs no one in; a second click still answers 204, until the link expires 72
hours after it was sent. An unknown, expired or voided link gets 400 `invalid-confirmation-link`, and each IP gets 30
posts an hour (`aulaflix.rate-limits.email-confirmations.*`). `POST /v1/account/confirmation-emails`, with the
Student's session, sends a new link and voids the earlier ones: 60 seconds after the latest link at the soonest, and at
most 5 times within 24 hours, past which it answers 429 with `Retry-After`; an email already confirmed gets 409
`email-already-confirmed`. `GET /v1/account` shows `emailConfirmed`. Nothing is gated on it.

A Student who forgot their password asks for a 6-digit code: `POST /v1/password-reset-codes` `{ email }` always
answers 204, and takes at least `aulaflix.password-reset.code-request-time` (300 ms) whatever the email, so that neither
the answer nor its timing tells who has an Account. Only a Student's email gets the code; an unknown or an Admin's email
gets nothing. A code lives 15 minutes, and a new one voids the earlier one, but not within 60 seconds of it, and not
past 10 codes per Account within 24 hours: past those, the request still answers 204 and sends nothing. Then
`POST /v1/password-resets` `{ email, code, newPassword }` sets the new password and answers 200 `{ token, expiresAt }`:
every earlier session of the Account ends, a squatter's included, a new one opens, the email counts as confirmed, a
sign-in block on it lifts, and the password-changed email is queued. The new password is checked before the code (its
format, then HIBP, as `newPassword`), so a refused one never spends a try. A code that is not exactly 6 digits is
`invalid-format`; a wrong, expired, voided or superseded code, none asked for, and an unknown or an Admin's email all
get 400 `invalid-code`, and the 5th wrong try voids the code. Per IP, the code request gets 20 a day and the reset 30
an hour, whatever they answer (`aulaflix.rate-limits.password-reset-codes.*`, `aulaflix.rate-limits.password-resets.*`).

A signed-in Student changes their password the same way, with the session: `POST /v1/account/password-change-codes`
answers 204 and emails a `CHANGE` code, under the same rules, but since the Student is known, a request within 60
seconds of the latest change code, or past 10 codes (of both kinds) within 24 hours, answers 429 with `Retry-After`.
Then `PUT /v1/account/password` `{ code, newPassword }` answers 204: this session goes on and every other session of the
Account ends, so a stolen one dies; the email counts as confirmed, and the password-changed email is queued. It checks
the new password before the code, as the reset does, and refuses a wrong, expired, voided or superseded code, or none
asked for, with 400 `invalid-code`. A `RESET` code never serves as a `CHANGE` code, nor the reverse. An Admin's token
gets 403.

The codes are drawn from a CSPRNG and stored only as an HMAC-SHA256 under the secret file `aulaflix.codes.hmac-key`,
which also covers the Account and the kind of code, `RESET` or `CHANGE`. A new key voids the codes already sent, and
nothing else.

### A CAPTCHA past the soft limits

Normal use never meets a CAPTCHA: one appears only past a soft limit, when traffic looks abusive. Per client IP, the
email look-up and sign-in together get 10 within 15 minutes, sign-up 3 an hour and the reset-code request 5 an hour;
for everyone at once, 300, 60 and 60 an hour (`aulaflix.soft-limits.<operation>.per-ip.*` and `.global.*`). Past
either, each request needs a fresh Cloudflare Turnstile token, which the BFF forwards in `AulaFlix-Captcha-Token`;
without one, or with one Turnstile rejects (invalid, expired after 5 minutes, or already spent), the answer is 429
`captcha-required`, without `Retry-After`, and the web shows Turnstile and sends the request again. The API verifies
each token at `aulaflix.turnstile.base-url`'s `siteverify` with the secret file `aulaflix.turnstile.secret-key` and the
client IP as `remoteip`; when `siteverify` fails or stays silent past `aulaflix.turnstile.timeout` (3 s), the answer is
503 `captcha-unavailable` with `Retry-After` (`aulaflix.turnstile.retry-after`, 10 s). The hard limits keep counting
every request, those with a solved CAPTCHA included. A client IP crossing a soft limit is logged once per window, at
WARN, and so is a global limit tripping, in a line of its own.

## Emails

Every email goes through the outbox (`outbox_emails`): it is rendered and queued in the transaction of what it tells
of, so a request never waits on SMTP, and the drainer sends it later. The drainer runs `aulaflix.outbox.drain-interval`
after the end of the drain before, and starts at most `aulaflix.outbox.send-rate.emails` sends within any
`aulaflix.outbox.send-rate.per`, a failed one included; production keeps that below the SES account's maximum. An
email the server fails is tried again a minute later, then twice as long after each failure, up to an hour, and is
never given up on; its `attempts` count every try. Admin mode runs no drainer. Every email comes from
`aulaflix.outbox.from`, `AulaFlix <contato@aulaflix.com.br>`.

SMTP is Spring's `spring.mail.*`, with timeouts that stop a silent server from holding the drainer. Locally it is
Mailpit, without TLS. Production points `spring.mail.host` at `email-smtp.sa-east-1.amazonaws.com`, port 587, with
the SES SMTP credentials as the secret files `spring.mail.username` and `spring.mail.password`, and requires STARTTLS:
`spring.mail.properties.mail.smtp.auth`, `….starttls.enable` and `….starttls.required` set to `true`. SES's ports 465
and 2465 take `spring.mail.ssl.enabled=true` instead.

## Meus cursos and Progress

With the Student's token, `GET /v1/account/enrollments` answers "Meus cursos": `{ items, highlightedCourseId? }`,
every active Enrollment, each with its `course` `{ id, slug, title, area, icon, tone, status }`, `progress`
`{ completed, published, total, percent, standing }` and `resumeLesson` `{ id, slug, number, title }`. `total` counts every Lesson, "Em breve" ones included, and
`percent` is `completed ÷ total` rounded down, so it reaches 100 only once the Course is `FINISHED`. The `standing` is
`NOT_STARTED`, `IN_PROGRESS`, `CAUGHT_UP` (every published Lesson done, some still "Em breve") or `FINISHED`.
`GET /v1/account/enrollments/{courseId}` answers one of them with its `completedLessonIds`, or 404
`enrollment-not-found` when the Student has no active Enrollment in the Course. An Enrollment in a Coming soon Course
comes without `progress` and `resumeLesson` (or `completedLessonIds`) until the launch, and is never highlighted.

The Lesson's page calls `POST /v1/account/lesson-visits` `{ lessonId }` when it mounts (no `GET` records a visit). It
answers 204, keeps only the last visit per Course, and has the same guards as the marks below; a missing or
non-numeric `lessonId` gets 400 `invalid-request`. The list puts the most recently visited Course first, and the
Courses never visited after them, oldest Enrollment first. `highlightedCourseId` ("Continuar de onde parou") is the
most recently visited Course with a published Lesson left to complete, and is omitted when none has one. The
`resumeLesson`, in the outline's current order, is the first that applies: the last Lesson opened, if not completed;
the next unfinished published Lesson after it; the first unfinished published Lesson (also the rule before any
visit); with every Lesson done, the first published Lesson. Its `number` is the Syllabus's.

`PUT` and `DELETE /v1/account/completed-lessons/{lessonId}` mark a Lesson as completed and take the mark back; both
answer 204 and are idempotent. Only a published Lesson of an On sale Course takes a mark (any other, an id of any shape
included, answers 404 `lesson-not-found`), and only with an active Enrollment in its Course (409
`enrollment-required`). The marks belong to the Student, not to the Enrollment: once it ends they are out of reach,
and a new Enrollment in the Course brings them back as they were.

## Orders

A Student buys an On sale Course by Pix on AulaFlix's page ([ADR 0006](docs/adr/0006-card-payments-on-asaas-pix-on-our-page.md)):
`POST /v1/account/orders` `{ courseId, method: "PIX", cpf? }` writes the Order, awaiting payment, at the Course's
current Pix price, then asks Asaas for a Pix charge under the Order's code, its `externalReference`, and answers 201
with the Order and its `pix` `{ qrCodePng, copyPasteCode, expiresAt }`, 30 minutes out. Asking again while that Order
awaits payment answers it with 200, and makes no new charge. The Student's first Pix needs their CPF, punctuation
allowed, whose check digits the API checks: it makes the Student's one Asaas customer, with Asaas's notifications off,
and is never stored nor logged; the Account keeps only the customer's id. An Admin gets 403; a Course that is not On
sale, `course-not-for-sale`; a Student already enrolled, `already-enrolled`.

When Asaas is down, slower than `aulaflix.asaas.timeout`, or answers 5xx or 429, the answer is 503
`payment-unavailable` with `Retry-After` (`aulaflix.asaas.retry-after`); any other 4xx is 502
`payment-provider-error`, logged at ERROR. Either way the Order is cancelled, and the charge made for it is deleted at
once: by its id, or, when Asaas never gave one, by searching its code. A charge that search misses is left to
reconciliation.

`GET /v1/account/orders` lists the Student's Orders, newest first, without the ones never paid (expired, cancelled or
declined) and without the QR code; `GET /v1/account/orders/{code}` reads one in any state, with the QR code while it
awaits payment, and answers another Student's code with 404 `order-not-found`. Placing Orders gets 10 an hour per
Student, 30 per IP and 500 for everyone, whatever they answer (`aulaflix.rate-limits.checkouts-per-student.*`,
`.checkouts-per-ip.*`, `.checkouts.*`).

The API calls `aulaflix.asaas.base-url`, Asaas's sandbox locally and its production API in the `production` profile,
with the key in Asaas's `access_token` header. In the tests WireMock plays Asaas.

### Payments, through Asaas's webhook

Only a charge Asaas confirms opens access. Asaas posts its events to `POST /v1/webhooks/asaas`, the only endpoint the
edge lets in from the internet, from Asaas's IPs alone; it takes neither the BFF's key nor a client IP, and the BFF's
limits don't count it. Asaas sends the token registered with the webhook in `asaas-access-token`, which the API
compares in constant time with the secret file `aulaflix.asaas.webhook-token`: a wrong or missing token gets 403
`invalid-webhook-token`, and nothing is stored. Anything with the right token is stored in `webhook_events` as it
arrived, by Asaas's event id, and answered 200 with an empty body at once: a repeated id stores nothing, an event the
API does not handle is stored as `IGNORED`, and a body that is no event is stored as `UNPROCESSABLE`, with a `WARN`. A
body over 256 KB gets 413 `content-too-large`.

The webhook worker runs `aulaflix.asaas.webhook-interval` after the end of its run before (5 s), never in admin mode.
For each pending `PAYMENT_CONFIRMED` or `PAYMENT_RECEIVED`, it re-reads the charge from Asaas with the API's key and
acts on that answer, never on the event's body. The charge must be the Order's, under its code as the external
reference and for its amount, or the event is `UNPROCESSABLE`, with a `WARN`; a charge that is not `CONFIRMED` or
`RECEIVED` grants nothing. A paid charge makes the Order `PAID`, with `paidAt`, and grants one Enrollment whose origin
is the Order, however many events arrive, and queues the purchase confirmation email. A Student who already has the
Course keeps the Enrollment they had, granted by hand or by another Order: this one is a Duplicate payment, `PAID`
with `duplicatePayment: true` in the Student's list and read, and grants nothing. Every Admin is emailed the
Duplicate payment alert, once, however many events arrive; it names the Order's code, to refund it by hand. While
Asaas cannot be reached the events wait for the next run; a re-read Asaas refuses is logged at ERROR, and the event is
`UNPROCESSABLE`.

`GET /v1/admin/enrollments` shows an Enrollment an Order granted with `origin: ORDER` and its `orderCode`. It ends only
with its Order: ending it by hand gets 409 `paid-enrollment`.

## The API's image and the `full` profile

The `Dockerfile` builds the API's image: the jar on a JRE, run as the unprivileged user 10001, with the heap at 75% of
the container's memory limit. `docker stop` ends it gracefully: the JVM gets the SIGTERM, Spring Boot lets the
requests in flight finish, for 30 seconds at most, and Compose waits 40 before it kills.

The `full` profile adds the API's image, built from this repository, and the web's, from `aulaflix-web`'s private
image on GHCR, so the Admin rehearses Course JSON files against the whole stack before production. The API waits for
PostgreSQL and the storage to be healthy and for `storage-init` to complete; it reaches the storage at
`storage:9000`, but signs URLs for `localhost:9000`, where curl and the browser reach it. Its emails go to Mailpit at
`mailpit:1025`, and show in its inbox at <http://localhost:8025>.

```shell
cp .env.example .env              # the web image's tag; nothing secret
docker login ghcr.io              # with a GitHub token that has read:packages
docker compose --profile full up -d --build
```

The API answers on `127.0.0.1:8080` and the web on `http://localhost:3001`. The web takes its config as `NUXT_*` variables: `NUXT_API_BASE_URL`, the Turnstile site key,
and `NUXT_BFF_KEY`, which the `web` service reads from `secrets/aulaflix.bff.key` as Nuxt's server starts, so it stays
out of the Compose file and of `docker inspect`. Until `aulaflix-web` moves its routes onto this API, its image
still reads none of those variables and shows its built-in catalog, so the rehearsal checks the API but not yet the
web. The API applies its migrations as it starts; then create the Admin:

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

## Deploying to production

Production is one VPS (ADR 0003), and each repository deploys its own image, so a web change never redeploys the API.
This repository carries:

- `deploy/compose.yaml`: the API, PostgreSQL, the storage and `storage-init`. PostgreSQL lives only on
  `aulaflix-data`, which is `internal: true`; the API and the storage also join `edge-aulaflix`, the external network
  the web and the edge share. The only published port is the API's, on the VPS's `127.0.0.1:8080`, for the SSH tunnel.
- `src/main/resources/application-production.properties`: every non-secret production setting, inside the image, turned
  on by `SPRING_PROFILES_ACTIVE=production` in the Compose file.
- `deploy/.env.example`: the image tag, and the list of secret files.
- `deploy/nginx/`: the AulaFlix server blocks for `vps-edge`, and the njs key that counts media connections per IPv4
  address or IPv6 /64. `EdgeServerBlocksTest` runs them in the nginx image, with `nginx -t` among its checks.
- `.github/workflows/deploy.yml`: on every push to `main`, the tests, then the image, pushed to GHCR under the commit's
  SHA, then `docker compose pull api && docker compose up -d api` over SSH.

### Setting up the VPS, once

```shell
# The repository, for deploy/ and storage/ (the API itself comes from GHCR), and the network the edge and web share
sudo install -d -o deploy -g deploy /srv/aulaflix
git clone https://github.com/dev-labs-ai/aulaflix-api.git /srv/aulaflix/aulaflix-api
docker network create --ipv6 edge-aulaflix
docker login ghcr.io       # with a GitHub token that has read:packages only, since the image is private

# The secrets: mode 600, in a directory only root enters. Paste each value given by a provider, then Ctrl-D
sudo install -d -m 700 /srv/aulaflix/secrets
sudo sh -c 'cd /srv/aulaflix/secrets && umask 077
    openssl rand -base64 24 > spring.datasource.password
    openssl rand -base64 32 > aulaflix.bff.key
    openssl rand -hex 10 > storage.root-user
    openssl rand -hex 24 > storage.root-password
    for key in read-only read-write; do
        openssl rand -hex 10 > aulaflix.storage.$key.access-key-id
        openssl rand -hex 20 > aulaflix.storage.$key.secret-access-key
    done
    openssl rand -base64 32 > aulaflix.codes.hmac-key
    openssl rand -base64 32 > aulaflix.waitlist.unsubscribe-key'
for name in aulaflix.asaas.api-key aulaflix.asaas.webhook-token spring.mail.username spring.mail.password \
        aulaflix.turnstile.secret-key minio.license; do
    sudo sh -c "umask 077; cat > /srv/aulaflix/secrets/$name"
done
# The API's image runs as uid 10001, and Compose mounts each file with its owner and mode, so the files the API reads
# become 10001's, still mode 600. The rest stay root's, read only by the root-run storage and storage-init.
sudo sh -c 'cd /srv/aulaflix/secrets && chown 10001:10001 $(ls | grep -v -x -e minio.license -e storage.root-user \
    -e storage.root-password)'

cd /srv/aulaflix/aulaflix-api/deploy
cp .env.example .env       # then set AULAFLIX_API_TAG to a commit GitHub Actions has pushed
docker compose up -d
docker compose run --rm api admin create --email you@example.com --name "Your Name"
```

`deploy/.env.example` lists every secret file, who reads it and what it holds. The unsubscribe key must survive every
redeploy: a new one breaks the links in emails already sent. If the web runs as a user other than root or 10001, give
`aulaflix.bff.key`, which its Compose file mounts too, the web's group and mode 640.

In GitHub, the repository secret `MINIO_LICENSE` holds the license for the tests, and the `production` environment
holds the deploy's SSH access: `VPS_HOST`, `VPS_USER` (in the `docker` group, owning `deploy/.env`), `VPS_SSH_KEY` (a
key for this workflow alone) and `VPS_KNOWN_HOSTS` (`ssh-keyscan` of the VPS, checked before anything is sent).

### Every deploy, and what it leaves alone

The workflow rewrites `AULAFLIX_API_TAG` in `deploy/.env`, pulls that image and recreates the API container alone:
PostgreSQL and the storage keep running, and `storage-init` runs again, which changes nothing. The API is one
container, so a deploy brings seconds of downtime.

The workflow never touches the clone. When `deploy/` or `storage/` change, pull them and apply them by hand:

```shell
cd /srv/aulaflix/aulaflix-api && git pull --ff-only && cd deploy && docker compose up -d
```

The owner reaches PostgreSQL with `docker compose exec postgres psql -U aulaflix`, the storage with `mc` in
`docker compose run --rm --entrypoint sh storage-init` (setting its alias as `storage/init.sh` does), and the Admin
endpoints and Swagger UI through `ssh -L 8080:127.0.0.1:8080`.

### The edge

`vps-edge` includes `deploy/nginx/aulaflix.conf` in its `http` block, mounts `aulaflix-media.js` at
`/etc/nginx/njs/`, loads `ngx_http_js_module` in its main context, joins `edge-aulaflix`, and serves certbot's
webroot from `/var/www/certbot` with one certificate for the four names at `/etc/letsencrypt/live/aulaflix.com.br/`.

- `aulaflix.com.br` sends every path to `web:3000`; `www` answers 301 to it.
- `media.aulaflix.com.br` passes only `GET`, `HEAD` and `PUT` under `/videos/` to `storage:9000`, with the `Host` the
  URL was signed for. A `GET` or `HEAD` counts against 6 connections per client and slows to 1 MB/s after its first
  4 MB; an upload, up to 5 GiB, streams through unlimited. The access log keeps the path without the presigned query.
- `api.aulaflix.com.br` passes only `POST /v1/webhooks/asaas`, from Asaas's four production IPs, with a 256 KB body
  limit, and blanks `AulaFlix-BFF-Key` and `AulaFlix-Client-IP`. Register the webhook in Asaas at
  `https://api.aulaflix.com.br/v1/webhooks/asaas`.
- Port 80 answers the ACME challenge on every name and sends the rest to HTTPS; on `api`, the rest gets a 404. Every
  proxied request carries `X-Forwarded-For $remote_addr`, and every HTTPS answer HSTS, without `includeSubDomains`.

## Tests

`./mvnw test` needs Docker: PostgreSQL and AIStor Free run in Testcontainers, AIStor with the license from
`secrets/minio.license`, which CI writes there from a secret. HIBP, Asaas and Turnstile's `siteverify` are played by WireMock, in the tests' JVM. Mailpit runs in Testcontainers too, behind a relay in the tests' JVM
that a test can take down or silence, and the tests read what it received through its REST API. No job runs on its
own in the tests: a test drains the outbox itself, once, synchronously. `./mvnw verify` also runs the `*IT` tests, which make the
signed uploads and playback requests over real HTTP. Mutation testing runs with
`./mvnw test-compile org.pitest:pitest-maven:mutationCoverage`, and its report lands in `target/pit-reports/`. It
mutates the `command`, `config`, `exception` and `service` packages. To check one slice, name the classes it changed:

```shell
./mvnw test-compile org.pitest:pitest-maven:mutationCoverage \
    -DtargetClasses=com.devlabs.aulaflix.service.OutlineService,com.devlabs.aulaflix.exception.ProblemHandler
```

PIT tests each mutated class in a JVM of its own, which boots the application and its own PostgreSQL container, and
runs four at once; `-Dthreads=` changes that.
