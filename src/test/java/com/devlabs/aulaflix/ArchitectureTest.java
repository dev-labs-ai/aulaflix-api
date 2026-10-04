package com.devlabs.aulaflix;

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.devlabs.aulaflix", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /** The command is the jar's other entry point, beside the controllers, and only the application class runs it. */
    @ArchTest
    static final ArchRule layersAreRespected = layeredArchitecture()
            .consideringAllDependencies()
            .withOptionalLayers(true)
            .layer("Application").definedBy("com.devlabs.aulaflix")
            .layer("Controller").definedBy("..controller..")
            .layer("Command").definedBy("..command..")
            .layer("Service").definedBy("..service..")
            .layer("Repository").definedBy("..repository..")
            .layer("Entity").definedBy("..domain.entity..")

            .whereLayer("Controller").mayNotBeAccessedByAnyLayer()
            .whereLayer("Command").mayOnlyBeAccessedByLayers("Application")
            .whereLayer("Service").mayOnlyBeAccessedByLayers("Controller", "Command")
            .whereLayer("Repository").mayOnlyBeAccessedByLayers("Service")
            .whereLayer("Entity").mayOnlyBeAccessedByLayers("Service", "Repository");

    @ArchTest
    static final ArchRule noCyclesBetweenPackages =
            slices().matching("com.devlabs.aulaflix.(*)..").should().beFreeOfCycles();
}
