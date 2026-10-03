# Video delivery from self-hosted S3 storage with expiring signed URLs

Research for [#4](https://github.com/dev-labs-ai/aulaflix-api/issues/4), carried out on 2026-10-03.

**Question.** How can Lesson videos in self-hosted MinIO reach the browser so that only Students with an
Enrollment can watch them, using expiring signed URLs, while the Free lesson stays public?

Every claim below links to its source. Claims marked **unverified** could not be confirmed from a first-party
source. Claims marked **inference** are reasoning from the cited facts, not facts stated by a source.

## Answer in brief

- **Do not build on MinIO community edition.** MinIO Inc. says the AGPLv3 edition "is no longer maintained. Its
  codebase is frozen, with no new features, bug fixes, or security patches"
  ([MinIO pricing FAQ](https://www.min.io/pricing)). The GitHub repo is archived. It ships no binaries or images. Two
  high-severity 2026 CVEs were fixed only in the commercial AIStor product. Both CVEs let an attacker write objects
  without a valid signature (see §1). The application should target the generic S3 API, so that the storage server
  can be chosen separately (see §1.4).
- **Recommended MVP approach: progressive MP4 with one presigned GET URL per playback.** Encode each Lesson once,
  offline, to H.264/AAC MP4 with `-movflags +faststart`, and keep it in a private bucket. The API checks the
  Enrollment (it skips the check for the Free lesson). It then presigns a GET URL against the storage server's public
  hostname, with an expiry long enough to cover a viewing session. The web page plays it in a plain `<video>`
  element. Seeking works through HTTP range requests. There are no playlists, no CORS and no player library.
- **Main trade-off.** There is no adaptive bitrate. The URL is also a bearer token: anyone holding it can watch or
  download the video until it expires. HLS with API-rewritten playlists is the upgrade path, and it uses the same
  bucket and signer.

## 1. MinIO community edition: status on 2026-10-03

### 1.1 Timeline (first-party evidence)

| Date | Event | Source |
|---|---|---|
| 2025-05-09 | `minio/minio` PR #21278 bumps the embedded console, labelled "Breaking change". The two console repos (`minio/console`, `minio/object-browser`) now return HTTP 404 on GitHub. | [PR #21278](https://github.com/minio/minio/pull/21278); 404 checked 2026-10-03 via `gh api repos/minio/console` and `gh api repos/minio/object-browser` |
| 2025-10-15 | README switched to "Source-Only Distribution": "We will no longer provide pre-compiled binary releases for the community version". | [commit 9e49d5e](https://github.com/minio/minio/commit/9e49d5e7a6) |
| 2025-10-16 | Last community release, `RELEASE.2025-10-15T17-29-55Z`. Its notes say "For container environments, please clone the source and build the latest container". | [release](https://github.com/minio/minio/releases/tag/RELEASE.2025-10-15T17-29-55Z) |
| 2025-11-20 | Last push to `minio/mc` (the CLI client). The repo is archived. | [minio/mc](https://github.com/minio/mc) (`gh api repos/minio/mc`: `archived=true`) |
| 2025-12-03 | README "maintenance mode" commit. | [commit 27742d4](https://github.com/minio/minio/commit/27742d469462e1561c776f88ca7a1f26816d69e2) |
| 2026-02-12 | Last commit on `master`. README now opens with "THIS REPOSITORY IS NO LONGER MAINTAINED" and points to AIStor Free and AIStor Enterprise. | [commit 7aac2a2](https://github.com/minio/minio/commit/7aac2a2c5b7c882e68c1ce017d8256be2feea27f), [README](https://github.com/minio/minio/blob/master/README.md) |
| 2026-04-24 | GitHub's `pushed_at` for the repo. It is now `archived=true` (read-only). The exact archival date is **unverified**: secondary sources disagree between 13 Feb and 25 Apr 2026. | [minio/minio](https://github.com/minio/minio) (`gh api repos/minio/minio`) |

The 2025 removal of admin features from the community console is reported widely. No MinIO text found during this
research states it outright, so the details are **unverified**. It does not affect this decision.

### 1.2 Distribution today (checked 2026-10-03)

- **Docker Hub.** `hub.docker.com/v2/repositories/minio/minio/` returns 404 "object not found". The `minio`
  namespace no longer lists a `minio` or `mc` repository
  ([Docker Hub API](https://hub.docker.com/v2/namespaces/minio/repositories?page_size=50)).
- **Legacy downloads.** The README says historical binaries "remain available" at `dl.min.io/server/minio/release/`
  ([README](https://github.com/minio/minio/blob/master/README.md)). In fact that path, and `dl.min.io/client/mc/...`,
  return **HTTP 410 Gone**.
- **The only community install path left** is building from source (`go install github.com/minio/minio@latest`, or
  `make docker`) ([README](https://github.com/minio/minio/blob/master/README.md)).
- **AIStor images** are still published at `quay.io/minio/aistor/minio`. The newest tag is
  `EDGE.2026-09-29T03-24-19Z` ([quay.io tag list](https://quay.io/v2/minio/aistor/minio/tags/list)).

### 1.3 Unpatched vulnerabilities, including one in presigned-URL handling

MinIO's own advisories list fixed versions that exist only in AIStor:

- **[CVE-2026-41145 / GHSA-hv4r-mvr4-25vw](https://github.com/minio/minio/security/advisories/GHSA-hv4r-mvr4-25vw)**
  (High, CVSS 4.0 8.8). This is a "Query-String Credential Signature Bypass in Unsigned-Trailer Uploads". It lets
  "any user who knows a valid access key [...] write arbitrary objects to any bucket without knowing the secret key".
  Affected versions: "All MinIO releases through the final release of the minio/minio open-source project". Fixed
  in: "MinIO AIStor RELEASE.2026-04-11T03-20-12Z".
- **[CVE-2026-40344 / GHSA-9c4q-hq6p-c237](https://github.com/minio/minio/security/advisories/GHSA-9c4q-hq6p-c237)**
  (High). An unauthenticated object write through Snowball auto-extract. It has the same precondition (a valid
  access key) and the same affected range.
- Other 2026 advisories list fixes only in post-community releases:
  [CVE-2026-39414](https://github.com/minio/minio/security/advisories/GHSA-h749-fxx7-pwpg),
  [CVE-2026-34204](https://github.com/minio/minio/security/advisories/GHSA-3rh2-v3gr-35p9),
  [CVE-2026-42600](https://github.com/minio/minio/security/advisories/GHSA-xh8f-g2qw-gcm7).

**Why this matters for this design.** Every SigV4 presigned URL carries the signing **access key ID** in plain sight
in `X-Amz-Credential`
([AWS IAM: authentication methods](https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_sigv-authentication-methods.html)).
On unpatched MinIO, every Student holding a video URL would therefore hold the one secret-free input the two CVEs
above need. Per the advisories, the precondition is a key "with WRITE permission on a bucket". So a **read-only**
signing key limits the damage (**inference**). Even so, it is not a reason to run unpatched software.

### 1.4 Alternatives (all self-hostable and S3-compatible)

| Option | License | Status on 2026-10-03 | Presigned GET / range reads |
|---|---|---|---|
| **MinIO AIStor Free** | Commercial. "Redistribution is prohibited" | Maintained. "Full AIStor feature set for a single-node deployment" ([pricing FAQ](https://www.min.io/pricing)). The download page asks for a license key ([download](https://min.io/download)). License text not reviewed (**unverified** terms). | Same S3 engine (**inference**) |
| **PGSTY Silo** (MinIO fork) | AGPL-3.0 | Released `RELEASE.2026-09-16`. It describes itself as "an independent, community-maintained fork", with images at `docker.io/pgsty/silo` ([README](https://github.com/pgsty/silo)). It patched GHSA-hv4r-mvr4-25vw and the Snowball path in [RELEASE.2026-04-17](https://github.com/pgsty/silo/releases/tag/RELEASE.2026-04-17T00-00-00Z). | Inherits MinIO's code |
| **RustFS** | Apache-2.0 | 1.0.0 GA on 2026-09-16, 1.0.1 on 2026-10-03 ([releases](https://github.com/rustfs/rustfs/releases)). Image `rustfs/rustfs` on Docker Hub. | "Presigned GET and PUT URLs: Supported"; "Range and conditional reads: Supported" ([S3 compatibility matrix](https://github.com/rustfs/rustfs/blob/main/docs/architecture/s3-compatibility-matrix.md)) |
| **SeaweedFS** | Apache-2.0 | 4.48 on 2026-09-28 ([releases](https://github.com/seaweedfs/seaweedfs/releases)) | Presigned URLs "Yes". GetObject "Supports range requests" ([wiki: Amazon S3 API](https://github.com/seaweedfs/seaweedfs/wiki/Amazon-S3-API)) |
| **Garage** | AGPL-3.0 | v2.4.1 on 2026-09-08 ([Forgejo repo](https://git.deuxfleurs.fr/Deuxfleurs/garage)) | Presigned URLs "Implemented" ([S3 compatibility](https://garagehq.deuxfleurs.fr/documentation/reference-manual/s3-compatibility/)). Range GET is not listed there (**unverified**). |

Notes:

- **SDK.** `minio/minio-java` is still maintained (Apache-2.0, 9.0.3 on 2026-06-12) ([repo](https://github.com/minio/minio-java)).
  However, the AWS SDK for Java v2 `S3Presigner` works against any of the servers above (§2.4). It keeps the API
  independent of the storage choice.
- **Next step.** Choosing the server belongs to the *Container topology* item on the map. This research does not
  pick one.

## 2. Presigned GET mechanics

### 2.1 Format and maximum expiry

- A presigned URL carries `X-Amz-Algorithm`, `X-Amz-Credential` (access key ID plus scope), `X-Amz-Date`,
  `X-Amz-Expires`, `X-Amz-SignedHeaders` and `X-Amz-Signature`
  ([AWS IAM: authentication methods](https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_sigv-authentication-methods.html)).
- **Maximum expiry is 7 days.** "`X-Amz-Expires` [...] The minimum value you can set is 1, and the maximum is
  604800 (seven days)", "because the signing key you use in signature calculation is valid for up to seven days"
  (same source).
  - MinIO enforces the same limit: `if preSignV4Values.Expires.Seconds() > 604800 { return psv, ErrMaximumExpires }`
    ([signature-v4-parser.go L231-L233](https://github.com/minio/minio/blob/7aac2a2c5b7c882e68c1ce017d8256be2feea27f/cmd/signature-v4-parser.go#L231-L233)).
  - The AWS SDK for Java v2 refuses longer durations at generation time: "Attempting to generate a signature longer
    than 7 days in the future will fail at generation time"
    ([S3Presigner javadoc](https://docs.aws.amazon.com/java/api/latest/software/amazon/awssdk/services/s3/presigner/S3Presigner.html)).
- **Expiry is checked on every request, not once per playback.** "Amazon S3 checks the expiration date and time of a
  signed URL at the time of the HTTP request [...] if the connection drops and the client tries to restart the
  download after the expiration time passes, the download fails"
  ([AWS: using presigned URLs](https://docs.aws.amazon.com/AmazonS3/latest/userguide/using-presigned-url.html)).
  - MinIO does the same per request: `if UTCNow().Sub(pSignValues.Date) > pSignValues.Expires { return ErrExpiredPresignRequest }`
    ([signature-v4.go L244-L245](https://github.com/minio/minio/blob/7aac2a2c5b7c882e68c1ce017d8256be2feea27f/cmd/signature-v4.go#L244-L245)).
  - A video player sends many range requests during one viewing (§3.1). So the expiry must cover the whole session,
    or the page must fetch a fresh URL when it expires (**inference**).
- **The URL is a bearer token.** "presigned URLs are bearer tokens that grant access to those who possess them", and
  "You can use the presigned URL multiple times, up to the expiration date and time"
  ([AWS: using presigned URLs](https://docs.aws.amazon.com/AmazonS3/latest/userguide/using-presigned-url.html)).
  - On AWS, a URL also dies early when the signing credential "is revoked, deleted, or deactivated" (same source).
    Whether every alternative server behaves the same way is **unverified**.
  - In practice, ending an Enrollment (for example after a Refund) does not cut off URLs already issued until they
    expire (**inference**).

### 2.2 How the signature binds to the host

- The host is always signed. "The following headers are required in the signature calculations: The HTTP host
  header" ([AWS IAM](https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_sigv-authentication-methods.html)).
  "You must include the host header (HTTP/1.1) or the :authority header (HTTP/2)"
  ([AWS IAM: create a signed request](https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_sigv-create-signed-request.html)).
- MinIO rejects a signature without `host`, and checks it against the `Host` it actually received (`r.Host`)
  ([signature-v4-utils.go L204-L208, L241-L243](https://github.com/minio/minio/blob/7aac2a2c5b7c882e68c1ce017d8256be2feea27f/cmd/signature-v4-utils.go#L204-L243)).
- **Consequence:** the URL must be signed for the exact hostname (and port) the browser will use. The reverse proxy
  must forward that `Host` header unchanged.

### 2.3 Behind a reverse proxy on a public hostname

- **Use a dedicated hostname.** MinIO's guide proxies the S3 API "to the root of that domain" and warns: "The S3 API
  signature calculation algorithm does *not* support proxy schemes where you host the MinIO Server API such as
  `example.net/s3/`" ([MinIO docs source: setup-nginx-proxy-with-minio.rst](https://github.com/minio/docs/blob/main/source/integrations/setup-nginx-proxy-with-minio.rst)).
  The published copy at `min.io/docs/...` now 301-redirects to the AIStor docs.
  - The guide's nginx config uses `proxy_set_header Host $http_host;`, `proxy_buffering off;` and
    `proxy_request_buffering off;` (same source).
- **Caddy** "passes through incoming headers—including `Host`—to the backend without modifications". Exception:
  "Since Caddy v2.11.0", when the upstream is **HTTPS**, Caddy sets `Host` to the upstream address automatically.
  That would break the signature unless overridden with `header_up Host {hostport}`
  ([Caddy reverse_proxy](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy)). Proxying over plain
  HTTP inside the Docker network avoids this (**inference**).
- **Traefik:** `passHostHeader` "Allows forwarding of the client Host header to server", default `true`
  ([Traefik service docs](https://doc.traefik.io/traefik/reference/routing-configuration/http/load-balancing/service/)).

### 2.4 Signing from the Spring API

- **Use two endpoints.** The API talks to storage over the internal network (for example `http://minio:9000`), but
  must **sign** for the public hostname (for example `https://media.<domain>`). `S3Presigner.Builder.endpointOverride`
  configures "an endpoint that should be used in the pre-signed requests"
  ([S3Presigner.Builder javadoc](https://docs.aws.amazon.com/java/api/latest/software/amazon/awssdk/services/s3/presigner/S3Presigner.Builder.html)).
  - Locally, the browser reaches storage at something like `http://localhost:9000`, so that is the presigner
    endpoint in the local profile (**inference**, from §2.2).
- **Keep the URL usable by a browser.** "Browser compatible" presigned requests "do not require the customer to send
  anything other than a 'host' header", and you can check this with `PresignedRequest.isBrowserExecutable()`.
  - Caution: when you pass a custom `S3Configuration` (for example to enable path-style access), checksum validation
    "must be explicitly disabled". Otherwise it adds a signed header and the URL stops being browser-compatible
    ([S3Presigner javadoc](https://docs.aws.amazon.com/java/api/latest/software/amazon/awssdk/services/s3/presigner/S3Presigner.html)).
- **Do not put typed `x-amz-*` fields on the presigned `GetObjectRequest`.** They become signed headers that a
  `<video>` element cannot send. Pass such values as signed query parameters with `putRawQueryParameter` instead
  ([AWS SDK for Java v2: presigned URLs](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/examples-s3-presign.html)).
- **Region.** If the server has no region configured, MinIO accepts any region in the credential scope
  (`if confRegion == "" { return true }`)
  ([signature-v4-utils.go L131-L144](https://github.com/minio/minio/blob/7aac2a2c5b7c882e68c1ce017d8256be2feea27f/cmd/signature-v4-utils.go#L131-L144)).

### 2.5 CORS

- **A plain `<video src>` with no `crossorigin` attribute needs no CORS.** "By default (that is, when the attribute
  is not specified), CORS is not used at all"
  ([MDN: crossorigin](https://developer.mozilla.org/en-US/docs/Web/HTML/Reference/Attributes/crossorigin)).
- **MSE-based players need CORS.** hls.js and video.js fetch segments with `fetch()`/`XMLHttpRequest`, which do use
  CORS ([MDN: CORS](https://developer.mozilla.org/en-US/docs/Web/HTTP/Guides/CORS)).
- **MinIO community has no per-bucket CORS.** Its bucket CORS endpoints are "a dummy call"
  ([api-router.go L462-L473](https://github.com/minio/minio/blob/7aac2a2c5b7c882e68c1ce017d8256be2feea27f/cmd/api-router.go#L462-L473)).
  CORS is server-wide via `MINIO_API_CORS_ALLOW_ORIGIN`, which defaults to `*`
  ([internal/config/api/api.go L222-L227](https://github.com/minio/minio/blob/7aac2a2c5b7c882e68c1ce017d8256be2feea27f/internal/config/api/api.go#L222-L227)).

## 3. Progressive MP4 versus HLS

### 3.1 Progressive MP4 with HTTP range requests

- **Seeking uses range requests.** "Media players that support random access" use them. The server signals support
  with `Accept-Ranges` and answers `206 Partial Content`
  ([MDN: range requests](https://developer.mozilla.org/en-US/docs/Web/HTTP/Guides/Range_requests)).
- **MinIO honours `Range` on GetObject**
  ([object-handlers.go L365-L373](https://github.com/minio/minio/blob/7aac2a2c5b7c882e68c1ce017d8256be2feea27f/cmd/object-handlers.go#L365-L373)).
  RustFS and SeaweedFS document range reads too (§1.4).
- **A browser's `Range` header does not break the signature.** Only `host` needs to be signed (§2.4). If `Range`
  itself were signed, `If-Range` would also have to be
  ([AWS: using presigned URLs, FAQ](https://docs.aws.amazon.com/AmazonS3/latest/userguide/using-presigned-url.html)).
  A plain GET presign avoids this.
- **Upshot:** one presigned URL per lesson is enough. The player sends many range requests against it, and each one
  must arrive before the expiry (§2.1).
- **Limits:** a single bitrate, with no adaptive switching. The whole file is downloadable by anyone holding the URL.

### 3.2 HLS, where every segment needs authorization

- **A signed playlist URL does not authorize its segments.**
  - HLS is a playlist of segment URIs. "Any relative URI is considered to be relative to the URI of the Playlist that
    contains it" ([RFC 8216 §4.1](https://www.rfc-editor.org/rfc/rfc8216#section-4.1)).
  - Under RFC 3986 resolution, a relative path reference takes **its own** query (`T.query = R.query`), not the
    base's ([RFC 3986 §5.2.2](https://www.rfc-editor.org/rfc/rfc3986#section-5.2.2)).
  - So the playlist's `X-Amz-*` query string never reaches the segments.
- **VOD playlists are loaded once.** The client reloads a Media Playlist "unless it contains an EXT-X-PLAYLIST-TYPE
  tag with a value of VOD" ([RFC 8216 §6.3.4](https://www.rfc-editor.org/rfc/rfc8216#section-6.3.4)). Any URLs
  embedded in it must therefore stay valid for the whole session.

| Option | How it works | Fit with native HLS (Safari) | Cost |
|---|---|---|---|
| **A. Rewritten playlist with per-segment presigning** | The API (reached through the Nitro BFF) serves the `.m3u8` itself, writing an absolute presigned URL for each segment. | Works: the URLs are inside the playlist. | One signature per segment (for example about 600 for a 1 h lesson with 6 s segments). Segment URLs must outlive the session. |
| **B. Single-file HLS** | `ffmpeg -hls_flags single_file` stores "all segments in a single MPEG-TS file, and will use byte ranges in the playlist" ([ffmpeg HLS muxer](https://ffmpeg.org/ffmpeg-formats.html#hls-2)), using `EXT-X-BYTERANGE`. The API rewrites one URI per rendition. | Works. | One signature per rendition. This amounts to range requests on one file plus adaptive bitrate (**inference**). |
| **C. Token checked at a proxy** | nginx `secure_link` checks an MD5 token over "the secured part of a link" plus expiry plus a secret; the module is "not built by default" ([nginx secure_link](https://nginx.org/en/docs/http/ngx_http_secure_link_module.html)). Or nginx `auth_request` sends a subrequest per media request, where "2xx [...] allowed" and "401 or 403 [...] denied" ([nginx auth_request](https://nginx.org/en/docs/http/ngx_http_auth_request_module.html)). The token can travel via `EXT-X-DEFINE:QUERYPARAM` ([draft-pantos-hls-rfc8216bis-22](https://www.ietf.org/archive/id/draft-pantos-hls-rfc8216bis-22.txt), a draft and not yet an RFC) or a JS request hook. | QUERYPARAM support in native Safari is **unverified**. JS hooks do not run under native playback (§5). | An extra proxy layer in front of storage. `auth_request` costs one API call per segment. |
| **D. AES-128 segments with an authorized key** | Segments are encrypted (`EXT-X-KEY:METHOD=AES-128`, [RFC 8216 §4.3.2.4](https://www.rfc-editor.org/rfc/rfc8216#section-4.3.2.4)), and only the key URI is protected. "The delivery of these keys SHOULD be secured by a mechanism such as HTTP Over TLS [...] in conjunction with a secure realm or a session token" ([RFC 8216 §10](https://www.rfc-editor.org/rfc/rfc8216#section-10)). The key could come from a BFF route behind the session cookie. | Works. | Segments can sit in public storage. This is not DRM: any enrolled viewer can fetch the key (**inference**). |

## 4. Transcoding (ffmpeg, faststart)

**Is it needed?** It depends on the source file.

- The most broadly compatible web format is "An MP4 container and the AVC (H.264) video codec, ideally with AAC as
  your audio codec". AVC plays in "All versions of Chrome, Edge, Firefox, Opera, and Safari"
  ([MDN: web video codecs](https://developer.mozilla.org/en-US/docs/Web/Media/Guides/Formats/Video_codecs)).
- If a recording is already H.264/AAC MP4, a **remux** is enough. Stream copy has "no decoding or encoding, it is
  very fast and there is no quality loss" ([ffmpeg: Streamcopy](https://ffmpeg.org/ffmpeg.html#Streamcopy)).
- Otherwise, **re-encode**.
- **Pixel format:** players such as QuickTime "only support the YUV planar color space with 4:2:0 chroma subsampling
  for H.264", so use `-pix_fmt yuv420p` ([ffmpeg wiki: H.264](https://trac.ffmpeg.org/wiki/Encode/H.264)).

**faststart.** A normal MP4 keeps its index at the end of the file. "It can be moved to the start for better playback
by adding +faststart to the -movflags" ([ffmpeg formats: MOV/MP4 fragmentation](https://ffmpeg.org/ffmpeg-formats.html#Fragmentation)).

- The `faststart` flag runs "a second pass moving the index (moov atom) to the beginning of the file"
  ([ffmpeg MOV/MP4 muxers](https://ffmpeg.org/ffmpeg-formats.html#MOV_002fMPEG_002d4_002fISOMBFF-muxers)).
- This lets the video "begin playing before it is completely downloaded"
  ([ffmpeg wiki: H.264](https://trac.ffmpeg.org/wiki/Encode/H.264)).

**Rate control.**

- CRF on its own is "not recommended for encoding videos for streaming". Use constrained encoding instead, for
  example `-crf 23 -maxrate 1M -bufsize 2M`. Note that "libx264 does not strictly control the maximum bit rate"
  ([ffmpeg wiki: H.264](https://trac.ffmpeg.org/wiki/Encode/H.264)).
- CRF's "subjectively sane range is 17–28", and a slower preset gives better compression (same source).

Example commands. The flags come from the sources above. The values (`3M`, `128k`, CRF 23) are starting points to
tune against real recordings, not sourced recommendations.

```sh
# Source already H.264/AAC MP4: remux only
ffmpeg -i in.mp4 -c copy -movflags +faststart out.mp4

# Anything else: re-encode to a single progressive rendition
ffmpeg -i in.mkv -c:v libx264 -preset slow -crf 23 -maxrate 3M -bufsize 6M \
  -pix_fmt yuv420p -c:a aac -b:a 128k -movflags +faststart out.mp4
```

**HLS later.** Apple's authoring spec says:

- "Key frames (IDRs) SHOULD be present every two seconds".
- "Target durations SHOULD be 6 seconds".
- VOD playlists "MUST add the EXT-X-PLAYLIST-TYPE with the value VOD".
- H.264 may use fMP4 or MPEG-TS segments.

Source: [Apple HLS authoring specification](https://developer.apple.com/documentation/http-live-streaming/hls-authoring-specification-for-apple-devices).

The matching ffmpeg options are:

- `-force_key_frames "expr:gte(t,n_forced*2)"` ([ffmpeg options](https://ffmpeg.org/ffmpeg.html)).
- `-hls_time 6 -hls_playlist_type vod`, plus optionally `-hls_segment_type fmp4`
  ([ffmpeg HLS muxer](https://ffmpeg.org/ffmpeg-formats.html#hls-2)).

**Where to run it.** For the MVP, run it offline on the author's machine before upload. Content is authored through
Admin API endpoints with no admin UI, so a server-side transcoding pipeline adds work without a user-facing gain
(**inference**).

## 5. Player choice

| Delivery | Player | Notes |
|---|---|---|
| Progressive MP4 | The native `<video>` element | Plays H.264/AAC MP4 in all major browsers ([MDN](https://developer.mozilla.org/en-US/docs/Web/Media/Guides/Formats/Video_codecs)). Needs no CORS (§2.5) and no library. Any UI wrapper can sit on top. |
| HLS | **hls.js** (Apache-2.0, v1.7.3 on 2026-09-11) | "only compatible with browsers supporting MediaSource extensions". Supports Safari on iOS 17.1+ through Managed Media Source. Supports `EXT-X-BYTERANGE` and `EXT-X-DEFINE` with `QUERYPARAM`. Offers `xhrSetup`/`fetchSetup` hooks to change requests ([README](https://github.com/video-dev/hls.js/blob/master/README.md), [API.md](https://github.com/video-dev/hls.js/blob/master/docs/API.md)). |
| HLS | **video.js 8** (Apache-2.0, v8.24.0 on 2026-08-03) with VHS | VHS is "included in the default build of Video.js since version 7". `overrideNative` is on "except on Safari", which plays natively. VHS exposes `vhs.xhr` hooks ([VHS README](https://github.com/videojs/http-streaming/blob/main/README.md), [releases](https://github.com/videojs/video.js/releases)). |
| HLS | Native (Safari) | Safari has "built-in HLS support through the plain video 'tag' source URL" ([hls.js README](https://github.com/video-dev/hls.js/blob/master/README.md)). The same README notes that Chrome 147 "reports support but may fail to play certain streams natively". |

JS request hooks (`xhrSetup`, `vhs.xhr`) apply only to MSE playback. Under native HLS the browser fetches segments
itself. So any authorization must live **in the playlist URLs** (options A, B or D) to cover native playback
(**inference** from the rows above).

## 6. Bandwidth from a single VPS

Egress is the binding constraint. It equals the encoded bitrate × concurrent viewers.

- **Typical bitrates.** Apple's example H.264 ladder for 16:9 content uses 3000 or 4500 kbit/s at 1280×720, and
  6000 or 7800 kbit/s at 1920×1080. "For VOD content, the peak bit rate SHOULD be no more than 200% of the average
  bit rate" ([Apple HLS authoring spec](https://developer.apple.com/documentation/http-live-streaming/hls-authoring-specification-for-apple-devices)).
  Screen recordings of code may compress far below film-oriented ladders (**unverified**). Measure the real
  recordings after encoding.
- **Arithmetic** (decimal units):
  - 3 Mbit/s × 3600 s = **1.35 GB per viewing hour**. At 6 Mbit/s it is 2.7 GB per hour.
  - Example month: 100 active Students × 8 h × 1.35 GB ≈ **1.1 TB of egress**.
  - Concurrency ceiling: port speed ÷ bitrate. That is about 33 streams at 3 Mbit/s on a 100 Mbit/s port, and
    about 333 on 1 Gbit/s, before overhead and the API's own traffic.
- **Billing.** Providers commonly bill outgoing traffic only. Hetzner, for example: "We only bill for outgoing
  traffic. Incoming and internal traffic is free" ([Hetzner billing FAQ](https://docs.hetzner.com/cloud/billing/faq/)).
  The owner's actual provider, port speed and included traffic are **unverified**, and should be checked before
  choosing the target bitrate.
- **Shared resources.** Video, API and database share one machine's uplink and disk. A burst of viewers can slow the
  API (**inference**). Serving files involves no transcoding at request time, so CPU cost is low (**inference**).

## 7. Recommended approach for the MVP

**Progressive MP4 with a per-playback presigned GET URL.**

1. **Encode offline**, once per Lesson. Produce one H.264/AAC MP4 rendition with `-movflags +faststart` and a
   constrained bitrate (§4). Upload it to a private bucket. How the Admin uploads (for example a presigned PUT
   issued by an Admin endpoint) is left to the spec.
2. **Issue a playback URL.** On playback, the browser asks Nitro, and Nitro calls the API. The API checks for an
   active Enrollment, and skips the check when the Lesson is the Free lesson, so one code path serves both and the
   bucket stays private. The API then presigns a GET with `S3Presigner` against the **public media hostname** and
   returns the URL with its expiry time.
3. **Choose the expiry deliberately.** It must outlast the range requests of one viewing session (§2.1). Bound it by
   the lesson's duration plus a margin of hours, never days. When a request fails after expiry, the page fetches a
   new URL and resumes at `currentTime` (**inference**).
4. **Play** the URL in a plain `<video>` element. This needs no CORS and no player library (§2.5, §5).
5. **Use a dedicated, read-only signing key** (GetObject on the video bucket only). Every URL exposes its access key
   ID, and the 2026 MinIO CVEs turned a write-capable key ID into a write primitive (§1.3).
6. **Infrastructure** (feeds the topology ticket):
   - Give storage its own hostname at the domain root, not a path prefix.
   - Forward `Host` unchanged at the proxy (§2.3).
   - Choose a maintained S3-compatible server instead of MinIO community (§1.4).

**What this trades away.**

- **No adaptive bitrate.** A viewer whose connection is slower than the encoded bitrate will buffer.
- **The URL is a bearer token.** Anyone holding it can share or download the file until expiry. Short expiries limit
  this but cannot stop an enrolled user from saving the file.
- **Revocation is not instant.** A Refund ends the Enrollment, but URLs already issued keep working until they
  expire.
- **Bandwidth is fixed per viewer**, and it all leaves one VPS uplink (§6).

**Upgrade path.**

- If buffering complaints or bandwidth costs appear, move to HLS with option A or B. The API rewrites the playlist
  with presigned segment URLs, using the same bucket, signer and Enrollment check. Option B needs only one signature
  per rendition.
- Add hls.js or video.js, plus server-wide CORS for the web origin, at that point.

## Open questions and unverified items

- Which S3-compatible server runs in production (AIStor Free, Silo, RustFS, SeaweedFS or Garage). The AIStor Free
  license terms were not read.
- The exact date `minio/minio` was archived, and the precise console features removed in 2025.
- Whether credential revocation kills already-issued presigned URLs on the chosen server, as it does on AWS.
- Garage's range-request support, and native-Safari support for `EXT-X-DEFINE:QUERYPARAM`.
- The owner's VPS provider, port speed and monthly egress allowance. The real bitrate of encoded screen recordings.
