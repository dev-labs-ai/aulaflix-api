package com.devlabs.aulaflix;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import java.util.Arrays;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.devlabs.aulaflix", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /**
     * The command is the jar's other entry point, beside the controllers, and only the application class runs it. The
     * security chain in config is the HTTP side's first step: it resolves session tokens through the session service.
     */
    @ArchTest
    static final ArchRule layersAreRespected = layeredArchitecture()
            .consideringAllDependencies()
            .withOptionalLayers(true)
            .layer("Application").definedBy("com.devlabs.aulaflix")
            .layer("Controller").definedBy("..controller..")
            .layer("Command").definedBy("..command..")
            .layer("Config").definedBy("..config..")
            .layer("Service").definedBy("..service..")
            .layer("Repository").definedBy("..repository..")
            .layer("Entity").definedBy("..domain.entity..")

            .whereLayer("Controller").mayNotBeAccessedByAnyLayer()
            .whereLayer("Command").mayOnlyBeAccessedByLayers("Application")
            .whereLayer("Service").mayOnlyBeAccessedByLayers("Controller", "Command", "Config")
            .whereLayer("Repository").mayOnlyBeAccessedByLayers("Service")
            .whereLayer("Entity").mayOnlyBeAccessedByLayers("Service", "Repository");

    @ArchTest
    static final ArchRule noCyclesBetweenPackages =
            slices().matching("com.devlabs.aulaflix.(*)..").should().beFreeOfCycles();

    /** The chain lets only Admins into {@code /v1/admin/**}; each endpoint says again who may call it. */
    @ArchTest
    static final ArchRule adminEndpointsCheckTheRoleAgain = methods()
            .that().arePublic()
            .and().areDeclaredInClassesThat(mappedUnder("/v1/admin"))
            .should().beAnnotatedWith(PreAuthorize.class)
            .because("ADMIN is checked twice: in the filter chain and by @PreAuthorize");

    private static DescribedPredicate<JavaClass> mappedUnder(String prefix) {
        return DescribedPredicate.describe("are mapped under " + prefix, type -> type
                .tryGetAnnotationOfType(RequestMapping.class)
                .map(mapping -> Arrays.stream(mapping.value()).anyMatch(path -> path.startsWith(prefix)))
                .orElse(false));
    }
}
