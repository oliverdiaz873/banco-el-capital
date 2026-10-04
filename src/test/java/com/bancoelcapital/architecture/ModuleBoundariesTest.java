package com.bancoelcapital.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Enforces ADR-01 dependency rules at build time. Accounts MUST NOT depend on Financial Operations
 * for financial logic. Empty packages are allowed until module code exists (Setup phase).
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
}
