---
name: java-conventions
description: "Java 21+ language and code-quality conventions shared by any backend framework choice: records as immutable DTOs, the Lombok ban, explicit (non-reflective) entity-to-DTO mapping, constructor-only dependency injection, SOLID/clean-code rules (method size, null safety, self-documenting names, layer boundaries), and ArchUnit architecture fitness-function tests that enforce those layer boundaries automatically. Use whenever writing or reviewing Java 21+ code for a backend service: creating a request/response DTO, mapping an entity to a DTO, wiring a class's dependencies, checking a method's size or complexity, deciding whether a class belongs in its current layer, setting up an automated architecture/layering test, or reviewing for SOLID/clean-code violations. Complements java-quarkus-standards and java-spring-standards, which own everything specific to their framework (package layout, persistence/threading mechanics) — this skill owns the parts of the standard that are Java-language-level, identical regardless of which framework the project chose."
---

# Java 21+ Conventions

These rules hold regardless of whether the project is Quarkus, Spring Boot, or something else entirely — they're
about Java as a language and about code quality, not about a framework's wiring.

## Modern Java & DTOs

- Target **Java 21+**; prefer records, sealed types, pattern matching, and enhanced switch where they read cleanly.
- Every request and response DTO **MUST** be a `public record`.
- **Lombok is strictly prohibited.** No `@Data`, `@Builder`, `@Getter`, no Lombok dependency in the build file.
- Validate with Jakarta Bean Validation annotations placed directly on record components.

## Entity ↔ DTO mapping

- **No reflection-based mappers** (ModelMapper, Dozer, and similar). Reflection over a record breaks its
  immutability guarantees, costs measurable startup time doing what a hand-written mapping does for free, and fails
  *silently* the moment a field is renamed on either side — the mapper doesn't error, it just stops populating that
  field. On a native-image build (GraalVM, common with Quarkus), reflection-based mapping also breaks compilation
  outright unless every reflected class is explicitly registered, which defeats the point of using one.
- Map explicitly: a static factory method on the response record, or a private mapping method in the service. Either
  is one file a reader can open to see the whole mapping, instead of trusting a library to have gotten it right.
- **MapStruct** is compile-time (not reflection-based) and acceptable **only** if the user explicitly asks for it;
  otherwise write the mapping by hand. It's a real, safe option — the default is "don't add a dependency the user
  didn't ask for," not "MapStruct is unsafe."
- No entity ever leaves the service layer — not as a return type from a resource/controller method, not nested
  inside another DTO. The mapping is where that boundary is actually enforced in code, not just stated as a rule.

## Dependency injection

**Always constructor injection** — declare a collaborator `private final` and take it as a constructor parameter.
Field injection is not used, in any DI framework: it hides a class's real dependencies from anything that isn't the
container, and makes the class impossible to instantiate — so impossible to unit test — without one. A single
constructor needs no injection annotation on it at all in either CDI or Spring; a class with more than one
constructor is the one case that still needs one, to tell the container which to use.

## SOLID and clean code

- **A class that reaches across two layers is split, not extended.** Query logic found in a service, or HTTP
  handling found in a repository, moves to where it belongs — widening the class that happens to hold it is how a
  layer stops meaning anything.
- **Cyclomatic complexity & method size:** keep methods short (under 20 lines) and focused. Use early returns (guard
  clauses) to minimize nested `if`/`else`.
- **Null safety & intentional returns:** never return `null` from a service method. Use `Optional<T>` for a
  potentially missing single entity, and an empty collection (`List.of()`) rather than `null` when no results match.
  When a service method exists to serve an endpoint that must answer 404, it still **throws** instead of returning
  an empty `Optional` — `Optional` is for callers that can meaningfully handle absence, not a substitute for the
  domain exception that maps to a client-facing error.
- **Self-documenting code:** choose clear, intention-revealing names for methods and variables. Avoid obscure
  abbreviations or comments that just restate what the code already says.

## Architecture fitness functions

The layer-boundary rule above ("a class that reaches across two layers is split, not extended")
is worth enforcing as a real, executable test instead of something re-derived by reading the diff
on every review. **ArchUnit** does this: a plain JUnit test that fails the build the moment a
controller touches a repository directly, or a cycle appears between packages.

Add it only when the project already has `archunit-junit5` on the test classpath, or the user
explicitly asks for an architecture/layering test — this is a new test dependency, and the rule
here is the same as everywhere else in this skill set: don't add one unprompted.

*Shape to copy: `references/archunit.md`.*
