# ArchUnit — architecture fitness functions

Companion to `SKILL.md`'s "Architecture fitness functions" section. Confirm the current
`archunit-junit5` version against Maven Central before pinning — do not guess it.

```xml
<dependency>
    <groupId>com.tngtech.archunit</groupId>
    <artifactId>archunit-junit5</artifactId>
    <version>1.5.0</version>
    <scope>test</scope>
</dependency>
```

```java
@AnalyzeClasses(packages = "<base.package>")
class ArchitectureTest {

    @ArchTest
    static final ArchRule layersAreRespected = layeredArchitecture()
            .consideringAllDependencies()
            .layer("Controller").definedBy("..controller..")
            .layer("Service").definedBy("..service..")
            .layer("Repository").definedBy("..repository..")
            .layer("Entity").definedBy("..domain.entity..")

            .whereLayer("Controller").mayNotBeAccessedByAnyLayer()
            .whereLayer("Service").mayOnlyBeAccessedByLayers("Controller")
            .whereLayer("Repository").mayOnlyBeAccessedByLayers("Service")
            .whereLayer("Entity").mayOnlyBeAccessedByLayers("Service", "Repository");

    @ArchTest
    static final ArchRule noCyclesBetweenPackages =
            slices().matching("<base.package>.(*)..").should().beFreeOfCycles();
}
```

`layeredArchitecture()` is what actually catches "a business rule landed in the controller" or "a
repository got instantiated in a layer that shouldn't touch it" — the exact violations a reviewer
otherwise has to re-derive by reading the diff every time. It runs on every `mvn test`, costs
milliseconds, and can't drift out of sync with what the skill says, because it *is* the rule,
executable — not a second, hand-maintained description of it.

The package names above (`controller`, `service`, `repository`, `domain.entity`) match this
project's convention from `java-quarkus-standards`/`java-spring-standards`; adjust them if a
project's layout differs. `<base.package>` is a placeholder, not a literal value — replace it with
the project's actual base package (see either framework skill's "Before writing code" section for
how to detect it).

`@AnalyzeClasses` and `@ArchTest` (from `archunit-junit5`) run every rule in the class
automatically — no `@Test` methods, no manual `new ClassFileImporter().importPackages(...)` setup.
`layeredArchitecture()` and `slices()` come from `com.tngtech.archunit.library.Architectures` and
`com.tngtech.archunit.lang.syntax.ArchRuleDefinition` respectively.
