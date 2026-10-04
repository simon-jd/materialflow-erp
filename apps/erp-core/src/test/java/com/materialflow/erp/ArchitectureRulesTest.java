package com.materialflow.erp;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.materialflow")
class ArchitectureRulesTest {
    @ArchTest
    static final ArchRule DOMAIN_IS_FRAMEWORK_INDEPENDENT = noClasses()
            .that().resideInAnyPackage(
                    "com.materialflow.pricing..",
                    "com.materialflow.credit..",
                    "com.materialflow.inventory..",
                    "com.materialflow.sales..",
                    "com.materialflow.approval..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "jakarta.persistence..", "org.apache.kafka..");

    @ArchTest
    static final ArchRule CONTROLLERS_DO_NOT_ACCESS_PERSISTENCE = noClasses()
            .that().haveSimpleNameEndingWith("Controller")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.jdbc..", "com.materialflow.erp.persistence..");

    @ArchTest
    static final ArchRule TRANSACTIONS_LIVE_ON_APPLICATION_SERVICES = methods()
            .that().areAnnotatedWith(org.springframework.transaction.annotation.Transactional.class)
            .should().beDeclaredInClassesThat().haveSimpleNameEndingWith("ApplicationService");
}
