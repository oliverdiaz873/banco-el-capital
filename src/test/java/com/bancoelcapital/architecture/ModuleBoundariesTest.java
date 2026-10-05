package com.bancoelcapital.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Enforces ADR-01 dependency rules at build time. Accounts depends on Identity contracts only; it
 * MUST NOT reach into Customers directly. Identity stays free of domain dependencies.
 */
class ModuleBoundariesTest {

  private final JavaClasses classes = new ClassFileImporter().importPackages("com.bancoelcapital");

  @Test
  void accountsMustNotDependOnFinancialOps() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("com.bancoelcapital.accounts..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.bancoelcapital.financialops..")
            .allowEmptyShould(true);
    rule.check(classes);
  }

  @Test
  void accountsMustNotDependOnCustomers() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("com.bancoelcapital.accounts..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.bancoelcapital.customers..");
    rule.check(classes);
  }

  @Test
  void identityMustNotDependOnAccounts() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("com.bancoelcapital.identity..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.bancoelcapital.accounts..");
    rule.check(classes);
  }

  @Test
  void identityMustNotDependOnCustomers() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("com.bancoelcapital.identity..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.bancoelcapital.customers..");
    rule.check(classes);
  }

  @Test
  void customersMustNotDependOnAccounts() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("com.bancoelcapital.customers..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.bancoelcapital.accounts..");
    rule.check(classes);
  }

  @Test
  void customersMustNotDependOnFinancialOps() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("com.bancoelcapital.customers..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.bancoelcapital.financialops..")
            .allowEmptyShould(true);
    rule.check(classes);
  }
}
