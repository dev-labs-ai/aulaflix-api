# Transactional email provider for a self-hosted API

Research for [#3](https://github.com/dev-labs-ai/aulaflix-api/issues/3). All facts were retrieved on **2026-10-03**
from the provider's own pages unless marked otherwise. Prices are in USD unless stated and **will drift**, so re-check
them before committing. Anything I could not confirm on a first-party page is marked **unverified**.

## Question

Which transactional email provider should the API use for email confirmation, password-reset codes and Waitlist
launch notifications, given that it runs in Docker on a self-hosted VPS? Candidates: Amazon SES, Resend, Brevo,
Postmark, Mailgun, and sending straight from the VPS. Volume: hundreds to a few thousand emails a month.

## Answer in one paragraph

Use **Amazon SES in the São Paulo region (`sa-east-1`)**, sending over **SMTP through `spring-boot-starter-mail`**,
with **Mailpit** standing in for it in local Compose and in tests. At MVP volume SES costs well under US$1 a month and
has no daily cap once the account leaves the sandbox. Email content stays in a Brazilian region. SMTP keeps the code
provider-agnostic, so switching providers is a configuration change. **The trade-off:** onboarding takes the most work
of any option. You need an AWS account and IAM-derived SMTP credentials, and you must request production access
before you can email real users. You also have to set up a bounce/complaint process yourself. **Resend** is the
fallback if SES access is slow or denied. It is the simplest to set up and also sends from São Paulo, but its free
tier stops at 100 emails a day.

## Comparison

Prices and free tiers were retrieved 2026-10-03.

| | Amazon SES | Resend | Brevo | Postmark | Mailgun | Self-hosted on the VPS |
|---|---|---|---|---|---|---|
| **Free tier** | No email allowance. New AWS customers get up to $200 Free Tier credits, usable for 6 months ([1]) | 3,000/month and 100/day, 3 domains ([10], [12]) | 300/day, covering campaigns and transactional together ([16]) | 100/month (developer plan, no expiry) ([20]) | 100/day, 1 domain, 1-day log retention ([24]) | n/a |
| **~3,000 emails/month** | $0.48 on Essentials ($0.16/1k), or $0.30 à la carte ($0.10/1k) ([1]) | Free, as long as no day goes over 100 ([12]) | Free, as long as no day goes over 300 ([16]) | $15/mo Basic (10k included) ([20]) | Free if ≤100/day, otherwise $15/mo Basic (10k) ([24]) | $0 provider cost |
| **~10,000 emails/month** | $1.60 Essentials, or $1.00 à la carte ([1]) | $20/mo Pro (50k, no daily cap) ([10]) | Starter "from 5,000 emails per month" at $9/mo (€7). Price of the 10k tier **unverified** ([16]) | $15/mo Basic ([20]) | $15/mo Basic ([24]) | $0 provider cost |
| **SMTP** | `email-smtp.sa-east-1.amazonaws.com`. STARTTLS on 25/587/2587, TLS on 465/2465 ([4], [5]) | `smtp.resend.com`. STARTTLS on 25/587/2587, TLS on 465/2465 ([11]) | `smtp-relay.brevo.com` on 587/2525, or 465 with TLS ([17], [18]) | `smtp.postmarkapp.com` on 25/2525/587 with STARTTLS ([21]) | `smtp.mailgun.org` / `smtp.eu.mailgun.org` on 25/465/587/2525 ([25], [26]) | Your own MTA |
| **HTTP API / Java client** | SES API v2. `software.amazon.awssdk:sesv2` 2.55.11 on Maven Central ([30]) | REST. `com.resend:resend-java` 4.28.0 on Maven Central ([13], [30]) | REST API. Java SDK **unverified** | REST API. Java client **unverified** | REST API. Java SDK **unverified** | n/a |
| **Data location** | The Region you choose. `sa-east-1` has both API and SMTP endpoints. AWS will not move content out of it "except as necessary to provide the services you initiated" or by law ([5], [8]) | Can send from `sa-east-1` ([14]). Where logs and metadata are stored is **unverified** | "Our servers are located in the EU" ([19]) | "we also store our data in the US" ([23]) | US or EU region. "message data never leaves the region in which it is processed" ([26]) | Your VPS |
| **Bounces and complaints** | Account-level suppression list covers bounces and complaints and is on by default for accounts created after 2019-11-25. Notifications go by email, SNS or event publishing ([6], [7]) | Automatic suppression on bounce or complaint. Webhooks `email.bounced`, `email.complained`, `email.suppressed` ([15], [31]) | Webhooks `hard_bounce`, `spam`, `blocked` ([32]). Hard bounces are blocklisted automatically (help-center snippet only, page returns 403: **unverified**) | Bounce webhook carries an `Inactive` flag ("this bounce caused the email address to be deactivated"). Spam complaints have a separate webhook ([22]) | Addresses on the "Do Not Send" lists are dropped ([27]) | You build it: parse DSNs, run feedback loops |
| **Domain DNS** | 3 DKIM CNAMEs, a MAIL FROM MX + SPF TXT, and DMARC (see below) ([3], [2], [9]) | DKIM TXT at `resend._domainkey`. SPF TXT + MX on a `send` subdomain. DMARC recommended ([33]) | Brevo-code TXT, DKIM and DMARC, all required (help-center snippet: **unverified**) | DKIM TXT. Return-Path CNAME to `pm.mtasv.net` for SPF alignment ([34], [35]) | SPF `include:mailgun.org`, DKIM TXT, tracking CNAME, MX ([28]) | SPF, DKIM signing, DMARC, PTR, TLS: all yours |
| **Gate before real sending** | Sandbox: verified recipients only, 200/24h, 1/s. Production request gets a first response within 24h ([4]) | None stated on the pages read | Account must be approved before sending ([16]) | Manual approval, under 24h on weekdays. Until then, only to your own verified domains ([36]) | **unverified** | Port 25 is often blocked by the VPS host ([38], [39]) |

**Deliverability:** I found no comparable first-party deliverability data. Marketing claims (e.g. "99% Deliverability" in
Brevo's page title) are not evidence. At our volume, three things drive inbox placement: authentication and alignment,
bounce/complaint hygiene, and the reputation of the provider's shared IPs. Dedicated IPs are out of reach anyway:
Resend offers them only above 3,000 emails/day ([10]) and Postmark only from 300,000/month ([20]). Treat any claim
that one provider "delivers better" as **unverified**.

## Provider notes

### Amazon SES

- **Pricing changed on 2026-07-21.** SES introduced Essentials, Pro and Enterprise plans ([37]). "New SES accounts
  and account x region combinations with no metered SES activity since June 1, 2025 will start on the Essentials plan
  beginning July 21, 2026." Essentials has a $0 monthly fee and costs $0.16 per 1,000 emails. À la carte remains
  $0.10 per 1,000, and attachments cost $0.12/GB ([1]). You can leave a plan with **Cancel plan**, which returns you
  to à la carte. If you were defaulted onto Essentials, your first cancellation takes effect immediately. Plans are
  set per account and per Region ([29]). Either way, MVP volume costs cents.
- **Sandbox.** New accounts can only send to verified addresses, at most 200 messages per 24h and 1 per second. The
  sandbox is per Region. The production-access request asks you to pick Marketing or Transactional and give a website
  URL. You must also confirm that you "only send email to individuals who've explicitly requested it" and "have a
  process in place for handling bounce and complaint notifications". AWS gives an initial response within 24 hours.
  If it needs more information, approval takes longer ([4]). Quota increases can be requested in the same form ([5b]).
  The quota granted on approval is **not documented** on the pages read.
- **Bounces and complaints.** SES places an account under review at a 5% bounce rate and may pause sending at 10%.
  For complaints the thresholds are 0.1% and 0.5% ([7b]). Only hard bounces go on the suppression list. "Gmail
  doesn't provide complaint data to SES," so Gmail spam reports never reach it ([6]). If you configure no
  notification method, SES forwards bounces and complaints by email to the Return-Path address ([7]).
- **SMTP credentials** are created through IAM, are unique to each Region, and differ from the AWS secret access key.
  The IAM user needs only `ses:SendRawEmail` ([5c]).

### Resend

- The free plan allows 100 emails/day and 3,000/month, and "Both sent and received messages count toward these
  limits". The rate limit is 10 requests/second per team ([12]). Pro costs $20/mo for 50,000 emails with no daily
  limit, and overage is $0.90/1k ([10]).
- Sending regions are `us-east-1`, `eu-west-1`, `sa-east-1` and `ap-northeast-1` ([14]).
- SMTP accepts the `Resend-Idempotency-Key` header ([11]).

### Brevo

- The Free plan sends up to 300 emails/day "once we approve your account for sending". The daily limit covers
  "campaigns & transactional" together ([16]).
- Removing the "Sent by Brevo" sticker is a paid add-on ([16]). Whether the sticker appears on *transactional* emails
  is **unverified**.
- The SMTP relay "does not support batch sending" ([17]).
- Servers are in the EU ([19]). That matters for LGPD (see [LGPD](#lgpd)).

### Postmark

- The policy is high-engagement transactional email only. Broadcasts go through a separate stream
  (`smtp-broadcasts.postmarkapp.com`, or the `X-PM-MESSAGE-STREAM` header) ([21]). A Waitlist launch email would need
  that broadcast stream.
- The free tier (100/month) is a development tier, not a production one ([20]). Data is stored in the US ([23]).

### Mailgun

- The free tier is 100/day with 1-day log retention ([24]). An EU region exists, and message data stays in its region
  ([26]).

### Sending straight from the VPS

- **Ports.** Hetzner Cloud blocks ports 25 and 465 by default. You can request unblocking "Once you have been with us
  for a month and paid your first invoice". Port 587 stays open for external mail services ([38]). DigitalOcean
  blocks 25, 465 **and 587** on Droplets and recommends a third-party provider ([39]).
- **Gmail requirements for every sender.** SPF or DKIM, valid forward and reverse DNS (PTR) for the sending IP, TLS,
  a spam rate below 0.3%, and RFC 5322 formatting ([40]). With your own MTA, IP reputation, PTR, DKIM signing and
  bounce processing are all your job. **Not recommended** for an MVP with real users.

## Rules that apply to every option

- **Gmail, all senders:** see the previous section ([40]). **Gmail, bulk senders (5,000+/day):** SPF *and* DKIM,
  DMARC, From-domain alignment with SPF or DKIM, and "Marketing messages and subscribed messages must support one-click
  unsubscribe" ([40]).
- **Outlook.com, high-volume senders (over 5,000/day):** SPF and DKIM must pass, and DMARC must be at least `p=none`
  and aligned with SPF or DKIM. Per the post's 2025-04-29 update, non-compliant mail is rejected with `550 5.7.515`.
  Effective from 2025-05-05 ([41]).
- The MVP will normally stay below 5,000/day. Set up full SPF + DKIM + DMARC alignment anyway: it costs nothing, and a
  Waitlist launch blast is exactly when you could approach the threshold.

## LGPD

This section gives the facts needed for a decision. It is not legal advice.

- ANPD Resolution CD/ANPD nº 19, of 2024-08-23, regulates international transfers (LGPD arts. 33–36). Controllers
  that rely on contractual clauses had to adopt ANPD's standard contractual clauses, unaltered, within 12 months of
  publication ([42], [43]).
- **Only the EU is recognised as adequate so far.** "Até o momento, a União Europeia foi considerada como organismo
  internacional adequado pelo Conselho Diretor da ANPD por meio da publicação da Resolução nº 32/2026" ([43]).
  Resolution 32 is dated 2026-01-26 ([44]). The EU adopted its matching adequacy decision for Brazil on 2026-01-27
  ([45]). The **US has no adequacy decision**, so a US-only processor needs another Art. 33 mechanism, such as the
  ANPD standard clauses.
- What follows for each candidate:
  - **SES in `sa-east-1`**: content stays in a Brazilian region under AWS's commitment ([8]).
  - **Brevo**: processes in the EU, which is covered by adequacy ([19]).
  - **Mailgun EU**: same as Brevo ([26]).
  - **Postmark**: US storage ([23]), so a transfer mechanism is needed.
  - **Resend**: sends from São Paulo ([14]), but where it stores data is **unverified**.
- Whichever provider you pick, sign its DPA. Ask counsel whether a US-headquartered processor with an in-Brazil region
  still counts as an international transfer.

## Sending from Spring Boot: SMTP vs. HTTP API

**What the build would pull in.** I checked Maven Central: `spring-boot-starter-mail` exists at 4.1.1, matching the
project's parent. It brings `spring-boot-mail` 4.1.1, which brings `spring-context-support` 7.0.9,
`jakarta.mail-api` 2.1.5 and `angus-mail` 2.0.5 ([30]). A `JavaMailSender` is auto-configured once `spring.mail.host`
is set. The docs warn that "certain default timeout values are infinite" ([46]), and Angus Mail confirms that
connection, read and write timeouts default to infinite ([47]).

**Properties.** From `spring-boot-mail-4.1.1.jar`'s configuration metadata:

- `spring.mail.host`, `port`, `username`, `password`
- `protocol` (default `smtp`) and `default-encoding` (default `UTF-8`)
- `properties.*`
- `ssl.enabled` (default `false`), `ssl.bundle`, `ssl.verify-hostname` (default `true`)
- `test-connection` (default `false`) and `jndi-name`
- `management.health.mail.enabled`, which defaults to **`true`** ([30])

| | SMTP via `spring-boot-starter-mail` | Provider HTTP API |
|---|---|---|
| Coupling | Provider-agnostic: switching provider means changing `spring.mail.*` | Vendor SDK or a hand-written `RestClient` call in code |
| Dev/test | Mailpit speaks SMTP, so local, test and prod share one code path | Mailpit does not emulate provider APIs, so you need an abstraction plus a second adapter |
| Network | Needs an open outbound submission port. SES and Resend offer 2587/2465 if 587/465 are blocked ([5], [11]) | HTTPS on 443 |
| Features | Plain MIME. Provider extras come through headers, e.g. Resend idempotency ([11]) or the Postmark stream ([21]) | Tags, configuration sets, idempotency keys and message IDs are first-class |
| Failure mode | Synchronous SMTP handshake. Set timeouts, and send outside the request transaction | Same concern, but a single HTTPS call |

**Recommendation:** SMTP, behind an application-level `EmailSender` port. Example production config for SES:

```yaml
spring:
  mail:
    host: email-smtp.sa-east-1.amazonaws.com
    port: 587                       # 2587 if the VPS blocks 587
    username: ${SES_SMTP_USERNAME}
    password: ${SES_SMTP_PASSWORD}
    properties:
      "[mail.smtp.auth]": true
      "[mail.smtp.starttls.enable]": true
      "[mail.smtp.starttls.required]": true
      "[mail.smtp.connectiontimeout]": 5000
      "[mail.smtp.timeout]": 3000
      "[mail.smtp.writetimeout]": 5000
```

The property names and meanings come from Angus Mail ([47]). The timeout keys follow the Spring Boot docs example
([46]).

**SES-backed `JavaMailSender` without SMTP.** Spring Cloud AWS provides one ([48]).
`io.awspring.cloud:spring-cloud-aws-starter-ses` is at 4.2.0 on Maven Central ([30]). Its compatibility with Spring Boot
4.1.1 is **unverified**: the project README lists 4.1.x for Boot 4.0.x and does not mention 4.1.

## Local stand-in for development and tests: Mailpit

- Mailpit "acts as an SMTP server, provides a modern web interface to view & test intercepted emails". It ships "A
  REST API for integration testing", HTML, link and spam checks, and SMTP relay/release ([49]).
- The image is `axllent/mailpit`, with SMTP on **1025** and the web UI and API on **8025**. Set `MP_DATABASE` for
  persistence ([50]). The latest release is v1.31.4, published 2026-10-03 ([51]).
- The API, from the v1.31.4 swagger, includes:
  - `GET /api/v1/messages` and `GET /api/v1/search`
  - `GET /api/v1/message/{ID}`
  - `DELETE /api/v1/messages`
  - `POST /api/v1/send`
  - `GET|PUT /api/v1/chaos` ([52])

  **Chaos** can fail the SMTP Sender, Recipient or Authentication stage with a 4xx/5xx code at a set probability. It
  is enabled with `--enable-chaos` ([53]). That lets you test the retry and failure paths.
- **Spring Boot 4.1.1 has no service connection for mail.** The Testcontainers `@ServiceConnection` list has no mail
  entry ([54]), and `spring-boot-mail-4.1.1.jar` contains no `ConnectionDetails` class ([30]). In tests, start a
  `GenericContainer` from `axllent/mailpit` and set `spring.mail.host`/`port` in `@DynamicPropertySource`. Then
  assert through `GET /api/v1/search`.
- A community module exists: `ch.martinelli.oss:testcontainers-mailpit` 1.3.1 on Maven Central, built against
  Testcontainers 2.0.3 and Spring Boot 4.0.3 ([30]). It is optional and small (28 GitHub stars).
- For a staging check against real SES, the SES mailbox simulator works even from the sandbox ([4]).

## Recommendation and trade-off

**Pick Amazon SES in `sa-east-1`, over SMTP, with Mailpit locally.**

Why:

1. **Cost.** At MVP volume SES is cheapest by an order of magnitude ([1]).
2. **No daily cap after production access.** That matters for a Waitlist launch blast. Resend's free tier stops at
   100/day ([12]) and Brevo's at 300/day ([16]).
3. **LGPD.** Content stays in a Brazilian region ([8]).
4. **Suppression is on by default** ([6]).
5. **SMTP keeps provider choice reversible.** If SES production access is slow or refused, the same code can point at
   Resend (`smtp.resend.com`, also from `sa-east-1`) ([11], [14]).

What you give up:

- **More onboarding than any other option:**
  - an AWS account
  - IAM SMTP credentials
  - DNS for DKIM and a custom MAIL FROM
  - a manual production-access review that AWS weighs "carefully" ([4])
- **More plumbing.** SES requires a bounce/complaint process, and complaint data from Gmail never arrives ([6]).
  Resend and Brevo give the nicest dashboards, webhooks and simple setup.
- **When a runner-up wins instead:**
  - *Resend Pro* ($20/mo), if setup time and developer experience matter more than about $20/month.
  - *Brevo*, if EU-hosted (adequacy-covered) processing and a large free tier matter more than API polish. Check the
    branding sticker first.

## DNS setup for SES (domain `example.com` as placeholder)

Create a **domain identity in `sa-east-1`** with Easy DKIM (2048-bit by default) ([3]). Then publish:

| Purpose | Name | Type | Value |
|---|---|---|---|
| DKIM (×3) | `<token1>._domainkey.example.com` … `<token3>._domainkey.example.com` | CNAME | `<tokenN>.<SigningHostedZone>`. For `sa-east-1` the DKIM domain is `dkim.amazonses.com`. Copy the exact values from the console or `GetEmailIdentity` ([2], [5]) |
| Custom MAIL FROM (SPF alignment, bounce feedback) | `bounce.example.com` (any unused subdomain) | MX | `10 feedback-smtp.sa-east-1.amazonses.com` ([9], [5]) |
| SPF for the MAIL FROM domain | `bounce.example.com` | TXT | `"v=spf1 include:amazonses.com ~all"` ([9]) |
| DMARC | `_dmarc.example.com` | TXT | Start with `"v=DMARC1; p=none; rua=mailto:dmarc-reports@example.com"`, then move to `p=quarantine`, then `p=reject` ([2b]) |

Notes:

- **One MX only.** The MAIL FROM subdomain must have exactly one MX record. It must not be a subdomain you send from
  or receive mail on. Choose "Reject message" or "Use default MAIL FROM domain" for MX failure ([9]).
- **Alignment.** DKIM alignment alone passes DMARC. The custom MAIL FROM also aligns SPF, as long as the DMARC
  record does not set strict SPF alignment (`aspf=s`) ([2b]).
- **Propagation.** DNS changes can take up to 72 hours. The identity shows **Verified** once SES sees all three DKIM
  CNAMEs ([2]).
- **Root SPF.** The domain's root SPF record does not need `amazonses.com`. Receivers check SPF on the MAIL FROM
  domain ([2b]).

## Findings that affect other parts of the plan

- **Container topology and config.**
  - Which submission port works depends on the VPS host. Hetzner blocks 25/465 but leaves 587 open; DigitalOcean also
    blocks 587 ([38], [39]). Make the SMTP port configurable; 2587 is the SES fallback ([5]).
  - SMTP credentials are a per-Region secret ([5c]).
  - Local Compose needs a `mailpit` service on ports 1025/8025 ([50]).
- **Actuator health.** `management.health.mail.enabled` defaults to `true` ([30]), so an SES outage would show in
  `/actuator/health`. Decide whether it belongs in readiness at all.
- **Auth design.**
  - SMTP timeouts default to infinite ([46], [47]). Send confirmation and reset emails outside the request
    transaction, e.g. via an outbox or async step, so a slow provider cannot block sign-up or leave a half-done
    transaction.
- **Waitlist launch notification.**
  - It is one-to-many and arguably a "subscribed message". Include an unsubscribe link and a `List-Unsubscribe`
    one-click header ([40]).
  - Size the SES daily quota request to the Waitlist ([5b]).
- **Possible endpoint.** Automated bounce/complaint handling via SNS needs a public HTTPS webhook in the API, with SNS
  message-signature checks. For the MVP, email feedback forwarding to a monitored mailbox plus the default suppression
  list is enough ([7], [6]). The webhook can come later.
- **Timing.** Request SES production access early, with the site URL live ([4]).

## Unverified or open

- Brevo: whether the "Sent by Brevo" sticker appears on transactional mail, the 10k-tier price, automatic hard-bounce
  blocklisting, and the exact DNS records (help-center pages return 403 to automated fetches).
- Resend: where it stores logs and metadata when sending from `sa-east-1`.
- Java SDKs for Brevo, Postmark and Mailgun.
- Whether Mailgun gates new accounts before real sending.
- The SES sending quota granted on production approval.
- Spring Cloud AWS 4.2.0 with Spring Boot 4.1.1.
- Any comparative deliverability claim.

## Sources

All retrieved 2026-10-03.

- [1] Amazon SES pricing — https://aws.amazon.com/ses/pricing/
- [2] SES: Creating and verifying identities (Easy DKIM CNAMEs) — https://docs.aws.amazon.com/ses/latest/dg/creating-identities.html
- [2b] SES: Complying with DMARC — https://docs.aws.amazon.com/ses/latest/dg/send-email-authentication-dmarc.html
- [3] SES: Easy DKIM — https://docs.aws.amazon.com/ses/latest/dg/send-email-authentication-dkim-easy.html
- [4] SES: Request production access (sandbox) — https://docs.aws.amazon.com/ses/latest/dg/request-production-access.html
- [5] SES endpoints and quotas (Regions, SMTP endpoints, DKIM domains, feedback endpoints) — https://docs.aws.amazon.com/general/latest/gr/ses.html ; SMTP ports and TLS modes — https://docs.aws.amazon.com/ses/latest/dg/smtp-connect.html
- [5b] SES: Managing sending limits — https://docs.aws.amazon.com/ses/latest/dg/manage-sending-quotas.html
- [5c] SES: Obtaining SMTP credentials — https://docs.aws.amazon.com/ses/latest/dg/smtp-credentials.html
- [6] SES: Account-level suppression list — https://docs.aws.amazon.com/ses/latest/dg/sending-email-suppression-list.html
- [7] SES: Event notifications — https://docs.aws.amazon.com/ses/latest/dg/monitor-sending-activity-using-notifications.html
- [7b] SES: Sending review process FAQs — https://docs.aws.amazon.com/ses/latest/dg/faqs-enforcement.html
- [8] AWS Data Privacy FAQ — https://aws.amazon.com/compliance/data-privacy-faq/
- [9] SES: Custom MAIL FROM domain — https://docs.aws.amazon.com/ses/latest/dg/mail-from.html
- [10] Resend pricing — https://resend.com/pricing
- [11] Resend: Send with SMTP — https://resend.com/docs/send-with-smtp
- [12] Resend: Account quotas and limits — https://resend.com/docs/knowledge-base/account-quotas-and-limits
- [13] Resend: Send with Java — https://resend.com/docs/send-with-java
- [14] Resend: Regions — https://resend.com/docs/dashboard/domains/regions
- [15] Resend: Email suppressions — https://resend.com/docs/dashboard/emails/email-suppressions
- [16] Brevo pricing (figures read from the page's embedded data) — https://www.brevo.com/pricing/
- [17] Brevo: SMTP relay integration — https://developers.brevo.com/docs/smtp-integration
- [18] Brevo: Node.js SMTP relay example (host name) — https://developers.brevo.com/docs/node-smtp-relay-example
- [19] Brevo: Data security — https://www.brevo.com/features/data-security/
- [20] Postmark pricing — https://postmarkapp.com/pricing
- [21] Postmark: Send email with SMTP — https://postmarkapp.com/developer/user-guide/send-email-with-smtp
- [22] Postmark: Bounce webhook — https://postmarkapp.com/developer/webhooks/bounce-webhook
- [23] Postmark: GDPR FAQ — https://postmarkapp.com/support/article/1218-gdpr-faq
- [24] Mailgun pricing — https://www.mailgun.com/pricing/
- [25] Mailgun: Send via SMTP — https://documentation.mailgun.com/docs/mailgun/user-manual/sending-messages/send-smtp
- [26] Mailgun: API overview (regions and endpoints) — https://documentation.mailgun.com/docs/mailgun/api-reference/api-overview
- [27] Mailgun: Tracking failures — https://documentation.mailgun.com/docs/mailgun/user-manual/tracking-messages/tracking-failures
- [28] Mailgun: Verify a domain — https://documentation.mailgun.com/docs/mailgun/user-manual/domains/domains-verify
- [29] SES: Pricing plans — https://docs.aws.amazon.com/ses/latest/dg/pricing-plans.html
- [30] Maven Central artifacts inspected directly. POMs: https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-starter-mail/4.1.1/ and https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-mail/4.1.1/ (jar metadata and class list). Version metadata for `software.amazon.awssdk:sesv2`, `com.resend:resend-java`, `io.awspring.cloud:spring-cloud-aws-starter-ses` and `ch.martinelli.oss:testcontainers-mailpit` under https://repo1.maven.org/maven2/
- [31] Resend: Webhook event types — https://resend.com/docs/webhooks/event-types
- [32] Brevo: Transactional webhooks — https://developers.brevo.com/docs/transactional-webhooks
- [33] Resend: Add a domain — https://resend.com/docs/add-a-domain
- [34] Postmark: DKIM setup — https://postmarkapp.com/support/article/1091-how-do-i-set-up-dkim-for-postmark
- [35] Postmark: Custom Return-Path — https://postmarkapp.com/support/article/910-adding-a-custom-return-path-domain
- [36] Postmark: Account approval — https://postmarkapp.com/support/article/1084-how-does-the-account-approval-process-work
- [37] AWS What's New, "Amazon SES introduces pricing plans" (posted 2026-07-21) — https://aws.amazon.com/about-aws/whats-new/2026/07/amazon-ses-pricing-plans/
- [38] Hetzner Cloud FAQ — https://docs.hetzner.com/cloud/servers/faq/
- [39] DigitalOcean: Why is SMTP blocked? — https://docs.digitalocean.com/support/why-is-smtp-blocked/
- [40] Google: Email sender guidelines — https://support.google.com/a/answer/81126
- [41] Microsoft: Outlook's requirements for high-volume senders (2025-04-02, updated 2025-04-29/30) — https://techcommunity.microsoft.com/blog/microsoftdefenderforoffice365blog/strengthening-email-ecosystem-outlook%e2%80%99s-new-requirements-for-high%e2%80%90volume-senders/4399730
- [42] ANPD: Resolução CD/ANPD nº 19/2024 — https://www.gov.br/anpd/pt-br/acesso-a-informacao/institucional/atos-normativos/regulamentacoes_anpd/resolucao-cd-anpd-no-19-de-23-de-agosto-de-2024
- [43] ANPD: Transferência Internacional de Dados — https://www.gov.br/anpd/pt-br/assuntos/assuntos-internacionais/transferencia-internacional-de-dados
- [44] ANPD: Resolution No. 32/2026 (English) — https://www.gov.br/anpd/pt-br/centrais-de-conteudo/outros-documentos-e-publicacoes-institucionais/resolucao-no-32-decisao-de-adequacao-uniao-europeia-em-lingua-inglesa.pdf/@@display-file/file
- [45] European Commission press release IP/26/229 — https://ec.europa.eu/commission/presscorner/api/files/document/print/en/ip_26_229/IP_26_229_EN.pdf
- [46] Spring Boot 4.1.1 reference: Sending Email — https://docs.spring.io/spring-boot/reference/io/email.html
- [47] Angus Mail SMTP provider properties — https://eclipse-ee4j.github.io/angus-mail/docs/api/org.eclipse.angus.mail/org/eclipse/angus/mail/smtp/package-summary.html
- [48] Spring Cloud AWS reference (SES) — https://docs.awspring.io/spring-cloud-aws/docs/current/reference/html/index.html
- [49] Mailpit docs — https://mailpit.axllent.org/docs/
- [50] Mailpit: Docker — https://mailpit.axllent.org/docs/install/docker/
- [51] Mailpit releases (v1.31.4, 2026-10-03) — https://github.com/axllent/mailpit/releases/latest
- [52] Mailpit API swagger at v1.31.4 — https://github.com/axllent/mailpit/blob/v1.31.4/server/ui/api/v1/swagger.json
- [53] Mailpit: Chaos — https://mailpit.axllent.org/docs/integration/chaos/
- [54] Spring Boot 4.1.1 reference: Testcontainers service connections — https://docs.spring.io/spring-boot/reference/testing/testcontainers.html
