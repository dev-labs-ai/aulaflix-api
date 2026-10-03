---
name: container-images
description: "Building production container images for a backend service: whether to hand-write a Dockerfile at all versus using the framework's own image-build tooling, multi-stage builds and layer caching, running as non-root, graceful shutdown and signal handling (exec vs. shell CMD form), image-size and base-image choice, and HEALTHCHECK versus Kubernetes probes. Use whenever writing or reviewing a Dockerfile, deciding how a service gets packaged into an image, debugging a slow or oversized image build, debugging a container that doesn't shut down gracefully, or setting up a health/liveness check for a containerized service. Language-agnostic; framework-specific asides (Quarkus, Spring Boot) note where the framework's own tooling already solves part of this."
---

# Container Images for Backend Services

A production image is judged on four things: how big it is, how fast it starts, whether it runs as an unprivileged
user, and whether it shuts down cleanly when the platform asks it to. Everything below serves one of those four.

## Consider not hand-writing a Dockerfile at all

Before writing a Dockerfile from scratch, check whether the framework's own image-build tooling already covers most
of what follows — it usually produces a smaller, better-layered, non-root image than a hand-rolled first attempt:

- **Spring Boot**: `./mvnw spring-boot:build-image` (Cloud Native Buildpacks, via Paketo) builds a layered OCI image
  with no Dockerfile at all.
- **Quarkus**: the `quarkus-container-image-*` extensions (`-docker`, `-jib`, `-buildpack`) build the image as part
  of `./mvnw package -Dquarkus.container-image.build=true`. Hand-written Dockerfiles under `src/main/docker/` are
  still a supported fallback when the extension's defaults aren't enough, not the only path.

Reach for a hand-written Dockerfile when the project needs something these don't cover (a non-JVM sidecar process,
an unusual base image requirement, a multi-service image) — not as the default starting point.

## Multi-stage build and layer caching

- **Build stage**: full JDK/SDK and build tool, produces the artifact. **Runtime stage**: only what's needed to run
  it (JRE, not JDK) — `COPY --from=build` pulls just the artifact across. The build toolchain never ships in the
  image that reaches production.
- **Order layers from least to most frequently changed.** Copy dependency descriptors (`pom.xml`, `build.gradle` +
  lock files) and resolve dependencies in their own layer *before* copying source code. A source-only edit then
  invalidates just the final layers, not a full dependency re-resolution. For Maven, `dependency:go-offline` in that
  early layer is the standard pattern — it isn't a hermetic guarantee (it's a known long-standing limitation that it
  doesn't always fetch every dependency), so keep network access available in the build stage rather than forcing
  `-o` (offline) afterward and having the build fail on whatever it missed.
- Prefer a minimal, actively-maintained runtime base image (a JRE-specific tag, a distroless image, or a
  vendor-minimal image) over a full OS image. Know the trade-off before picking one: a distroless-style image has no
  shell, which is good for attack surface but means `kubectl exec ... sh` won't work for live debugging — that's a
  deliberate trade, not a surprise to discover during an incident.

## Run as non-root

Add a non-root `USER` in the runtime stage; don't rely on the base image's default. Root inside a container is still
root if the container ever escapes its isolation, and several platforms (OpenShift, pod security policies/standards
elsewhere) simply refuse to schedule a container that requests root.

## Signal handling and graceful shutdown

- **Use the exec form of `CMD`/`ENTRYPOINT`**: `["java", "-jar", "app.jar"]`, not the shell form
  (`CMD java -jar app.jar`). The shell form runs the process as a child of `/bin/sh -c`, and that shell does not
  forward `SIGTERM` to its child by default — `docker stop` (or a Kubernetes pod termination) then waits out the
  full grace period with no graceful shutdown at all, and the runtime SIGKILLs the process instead. Exec form makes
  your process PID 1 and lets it receive the signal directly.
- **Being PID 1 is necessary, not sufficient.** The process still needs an actual `SIGTERM` handler to shut down
  gracefully instead of just dying — most backend frameworks install one, but not always by default. Verify graceful
  shutdown is actually enabled (e.g. Spring Boot's `server.shutdown`, `graceful` by default in 3.5 and 4 — check that
  nothing overrides it to `immediate`; Quarkus's `quarkus.shutdown.timeout`) rather than assuming exec-form `CMD`
  alone gets you a clean shutdown — it only gets the signal there, not a handler for it.
- Give the shutdown enough time: the platform's own termination grace period (Kubernetes' `terminationGracePeriodSeconds`,
  Docker's `stop --time`) has to be longer than however long the app's graceful shutdown actually takes to drain
  in-flight requests, or the SIGKILL arrives before it finishes anyway.

## HEALTHCHECK vs. platform probes

- **A Dockerfile's `HEALTHCHECK` instruction is inert under Kubernetes** — kubelet does not read or use it at all;
  it relies entirely on the Pod spec's own `livenessProbe`, `readinessProbe`, and `startupProbe`. Adding `HEALTHCHECK`
  to an image that only ever runs under Kubernetes isn't harmful, just pointless — don't spend effort tuning it
  there.
- `HEALTHCHECK` is genuinely useful for `docker run`/Compose-based deployments, which have no other health-checking
  mechanism.
- When probes are the real mechanism (Kubernetes), know the three have different jobs: **readiness** governs
  whether traffic is routed to the pod at all (fails closed, no restart); **liveness** restarts the pod when it
  fails; **startup** protects a slow-starting app from being killed by liveness before it's had a chance to come up.
  A liveness probe with too short an initial delay killing an app mid-startup is a common, avoidable outage.

## Image size and `.dockerignore`

- A `.dockerignore` excludes `.git`, build output directories that aren't the final artifact, IDE metadata, local
  `.env` files, and anything else that shouldn't reach the build context — both for image size and because the
  build context is sent to the Docker daemon wholesale before the first instruction runs.
- Never bake a secret into an image layer, even one later removed in a subsequent `RUN` — layers are additive, and a
  credential written in an earlier layer is still recoverable from the image history even after a later layer
  deletes the file. Secrets are injected at runtime (environment variables, a mounted secret volume), never copied
  in at build time.

## Before merging a Dockerfile

- [ ] The framework's own build-image tooling was actually considered and rejected for a specific reason, not
      skipped by default
- [ ] Multi-stage: the runtime image contains no build toolchain (no JDK when a JRE would do, no build tool)
- [ ] Dependency resolution is its own layer, ordered before the source code copy
- [ ] Runtime stage runs as a non-root `USER`
- [ ] `CMD`/`ENTRYPOINT` uses exec form, and the app's graceful-shutdown config is verified on, not assumed
- [ ] `HEALTHCHECK` is either omitted (Kubernetes-only deployment) or actually exercised (Compose/plain Docker
      deployment) — not left in as unverified copy-paste from a template
- [ ] `.dockerignore` exists and excludes `.git`, build artifacts, and any local secret files
- [ ] No secret was ever `COPY`'d or `RUN`-echoed into a layer, even one deleted later in the same Dockerfile
