---
name: security-scanning
description: "Automated, tool-based security scanning that complements manual/agent-based review: software composition analysis (SCA) for known-CVE dependencies via OWASP Dependency-Check (Maven), static application security testing (SAST) via SpotBugs+FindSecBugs or Semgrep, layered secret scanning via gitleaks (pre-commit) and a verifying scanner in CI, and container image vulnerability scanning via Trivy/Grype. Use whenever setting up a CI security gate, choosing or configuring a dependency-vulnerability scanner, adding a SAST tool to the build, setting up secret detection, or scanning a built container image for known CVEs. Complements api-security (design principles) and the security-auditor subagent (manual, judgment-based exploit hunting for authorization/business-logic flaws) — this skill owns the vulnerability classes a pattern-matching or database-lookup tool catches reliably and a code reader does not: known CVEs in a dependency or base image, common insecure-code patterns, and leaked credentials. Also complements container-images (Dockerfile design) for the image this skill's scanning step targets, and the Maven-only build-tooling scope this blueprint already assumes."
---

# Security Scanning

A known CVE sitting in a dependency, or a credential accidentally committed, isn't a code-reading
problem — it's a database-lookup problem and a leak-detection problem, respectively. Neither
benefits from a smarter reader; both need a scanner with a current feed, running on every commit.
That's what this skill covers, and it's why it's a different discipline from `api-security`
(design principles a human or agent applies while writing code) and the `security-auditor`
subagent (periodic, judgment-based hunting for the authorization and business-logic flaws that
structurally can't be caught by pattern-matching).

## Software composition analysis (SCA) — known-CVE dependencies

- **OWASP Dependency-Check** (`dependency-check-maven`) is the tool for this project's Maven-only
  scope: it resolves the dependency tree, maps each artifact to CPE identifiers, and cross-checks
  the NVD for published CVEs. Bind it to the `verify` phase so it runs in the same build lifecycle
  as tests, and set `failBuildOnCVSSScore` (e.g. `7`) so a build fails on a new high/critical
  finding instead of the report being generated and never read.
- **Get an NVD API key** (`nvdApiKey`, or the `NVD_API_KEY` environment variable) before relying on
  this in CI. The public NVD feed is aggressively rate-limited without one, and an unauthenticated
  CI job can spend most of its time waiting out that limit instead of scanning.
- **Cache the vulnerability database** across CI runs (it's large and slow to rebuild from
  scratch every time) rather than re-downloading the full NVD feed on every job.
- **Suppress a finding only with a written justification** in the suppression file (why it's a
  false positive, or why the actual risk doesn't apply here) — a suppression file is a second
  place vulnerabilities can hide if it turns into a blanket dumping ground instead of a
  case-by-case, reviewed exception list.

## Static application security testing (SAST)

- **SpotBugs + the FindSecBugs plugin** is the zero-config Java-native default: add
  `findsecbugs-plugin` as a `spotbugs-maven-plugin` plugin dependency and it runs on the same
  bytecode SpotBugs already analyzes for correctness bugs. No new language or DSL to learn, and
  it already runs in most Java CI pipelines for non-security reasons.
- **Semgrep** is worth adding alongside it, not instead of it, when the project wants custom rules
  against its own patterns (e.g. codifying part of `java-conventions` or `jpa-conventions` as an
  automated, enforced rule rather than something only a reviewer checks). SAST tools disagree more
  than their marketing suggests — different engines catch different, largely non-overlapping
  findings — so "we already run one SAST tool" is not a reason to skip evaluating a second one for
  a specific gap.
- **Treat findings as advisory by default, but gate specific categories.** A blanket "SAST must be
  zero findings" policy usually just trains everyone to ignore the tool once false positives pile
  up. A finding in a security-relevant category (hardcoded credential, SQL built by
  concatenation, deserialization of untrusted data) is a release blocker; a style-adjacent finding
  is a backlog item.

## Secret scanning

- **Layer a fast, cheap pre-commit check with a deeper, verifying CI check** — this is the
  standard split, not an either/or choice: **gitleaks** at pre-commit (regex-based, fast enough to
  run on every commit locally, blocks the obvious case before it ever reaches the remote), and a
  scanner that verifies whether a detected credential is still *active* (not just pattern-shaped)
  in CI, scanning the pull request's diff rather than the full history on every run — a full-repo
  history scan on every PR is slow and just re-reports the same already-known legacy leaks.
- **Run one full-history scan periodically** (not on every PR) as the audit layer that catches
  whatever predates the scanning setup — the pre-commit/PR-diff layers only protect what's
  committed from here forward.
- An allow-list entry for a detected "secret" (a fixture, an example credential in test code)
  needs the same discipline as a Dependency-Check suppression: a written reason, reviewed, not a
  blanket path exclusion that quietly also hides a real leak later added under the same path.

## Container image vulnerability scanning

- **Trivy or Grype scan the built image itself** — OS packages and JVM dependencies baked into
  the final layer — which is the complement to `container-images`' Dockerfile-design rules: a
  correctly multi-staged, non-root image can still ship a vulnerable base-image package that no
  amount of Dockerfile-design review would catch, because it isn't a Dockerfile problem.
- Run the scan in CI right after the image builds and before it's pushed to a registry, gated on
  a severity threshold the same way Dependency-Check gates on CVSS.
- **Rescan images already sitting in the registry on a schedule**, not just at build time — a CVE
  can be published for a package that was already clean when the image was built and pushed.

## How this relates to `security-auditor`

None of the above catches a broken authorization check, a mass-assignment field, or a business-
flow abuse — those require understanding what an endpoint is *supposed* to allow, which a
CVE-database lookup or a syntactic pattern match cannot determine. Automated scanning is the
floor everything runs on every commit; `security-auditor` is the periodic, judgment-based ceiling
for the vulnerability classes the floor structurally can't reach. Neither replaces the other.

## Before relying on this in CI

- [ ] SCA (Dependency-Check or equivalent) runs at `verify`, gated on a CVSS threshold, with an
      NVD API key configured and the vulnerability database cached across runs
- [ ] Every suppression/allow-list entry (Dependency-Check, secret scanner) carries a written
      reason, not a blanket path exclusion
- [ ] SAST (SpotBugs+FindSecBugs at minimum) runs on every build; security-relevant finding
      categories are release blockers, not just advisory
- [ ] A secret scanner runs pre-commit, and a verifying scanner runs in CI against the PR diff
- [ ] A periodic full-history secret scan exists, separate from the per-PR check
- [ ] The built container image is scanned before push, and rescanned on a schedule after
- [ ] `security-auditor` is still invoked periodically — none of the above substitutes for it
