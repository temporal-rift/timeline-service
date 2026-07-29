package io.github.temporalrift.timeline;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.beans.factory.annotation.Autowired;

@AnalyzeClasses(packages = "io.github.temporalrift.timeline", importOptions = ImportOption.DoNotIncludeTests.class)
public class ArchitectureTest {

    @ArchTest
    static final ArchRule domain_must_not_depend_on_spring_or_persistence = noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework..", "jakarta.persistence..", "lombok..")
            .as("Domain layer must be plain Java — no Spring, JPA, or Lombok");

    @ArchTest
    static final ArchRule application_must_not_depend_on_infrastructure = noClasses()
            .that()
            .resideInAPackage("..application..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..infrastructure..")
            .as("Application layer must not depend on infrastructure")
            // The application layer has no classes yet (arrives with the resolution use cases in MVP 2);
            // the rule stands so it enforces the boundary the moment those classes land.
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule driven_ports_must_only_be_implemented_in_infrastructure = noClasses()
            .that()
            .resideOutsideOfPackage("..infrastructure.adapter.out..")
            .should()
            .implement(resideInAPackage("..domain.port.out.."))
            .as("Driven ports must only be implemented in infrastructure.adapter.out");

    @ArchTest
    static final ArchRule no_field_injection = noFields()
            .that()
            .areNotAnnotatedWith(jakarta.persistence.PersistenceContext.class)
            .should()
            .beAnnotatedWith(Autowired.class)
            .as("Use constructor injection — never @Autowired on fields");
}
