package com.lumix.template.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Hexagonal bağımlılık yönü kuralını CI'da otomatik denetler (bkz. 03-hexagonal §4.2).
 * bootstrap tüm modülleri gördüğü için bu test burada yaşar.
 */
@AnalyzeClasses(
        packages = "com.lumix.template",
        importOptions = {ImportOption.DoNotIncludeTests.class})
public class HexagonalArchitectureTest {

    @ArchTest
    static final ArchRule domain_application_ve_adapteri_bilmez = noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..application..", "..adapter..");

    @ArchTest
    static final ArchRule application_adapteri_bilmez = noClasses()
            .that()
            .resideInAPackage("..application..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..adapter..");

    @ArchTest
    static final ArchRule domain_framework_bagimsizdir = noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework..", "jakarta..", "javax..");
}
