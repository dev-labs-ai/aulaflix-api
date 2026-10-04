# Self-hosted on one VPS, with video in AIStor Free

Everything runs in Docker on the owner's VPS (Hostinger, in Brazil): the API, PostgreSQL, the object storage and the
web. Lesson videos live in AIStor Free and reach the browser through presigned URLs. MinIO was the original choice,
but its community edition is frozen, with no security patches, archived repositories and no published images, and it
carries unpatched CVEs that matter for presigned URLs
([research](https://github.com/dev-labs-ai/aulaflix-api/issues/4)). AIStor Free is MinIO's maintained free edition.
The API reaches storage only through the generic S3 API and signs with a read-only key, so the server can be swapped
without touching code.

## Consequences

- The storage needs its own public hostname, and the reverse proxy must pass `Host` through unchanged, or the
  signatures break.
- Video traffic comes out of the VPS's monthly allowance; the 200 GB disk is the tighter limit on how much video fits.
- AIStor Free is single-node, requires a license file and forbids redistribution. Its license terms, read after this
  was decided, allow commercial production use; the license belongs to the account, never expires and serves every
  deployment, tests included, but every container needs it. Free has no encryption at rest, and one disk gives no
  redundancy ([decision](https://github.com/dev-labs-ai/aulaflix-api/issues/17)).
