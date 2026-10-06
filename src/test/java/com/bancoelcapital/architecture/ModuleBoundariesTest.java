package com.bancoelcapital.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Enforces ADR-01 dependency rules at build time. Accounts depends on Identity contracts only; it
 * MUST NOT reach into Customers directly. Identity stays free of domain dependencies. Financial
 * Operations coordinates through the Accounts application contract and AuditRecorder only; it MUST
 * NOT touch repositories or entities of other modules. API talks to services only.
 *
 * <p>Rules govern production code only: integration tests legitimately orchestrate modules through
 * their public service contracts to set up cross-module state.
 */
class ModuleBoundariesTest {

  private final JavaClasses classes =
      new ClassFileImporter().importPath(Paths.get("target/classes"));

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

  @Test
  void financialOpsMustNotDependOnCustomers() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("com.bancoelcapital.financialops..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.bancoelcapital.customers..");
    rule.check(classes);
  }

  @Test
  void financialOpsMustNotDependOnAccountRepository() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("com.bancoelcapital.financialops..")
            .should()
            .dependOnClassesThat()
            .haveSimpleName("AccountRepository");
    rule.check(classes);
  }

  @Test
  void financialOpsMustNotDependOnAccountEntity() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("com.bancoelcapital.financialops..")
            .should()
            .dependOnClassesThat()
            .haveSimpleName("Account");
    rule.check(classes);
  }

  @Test
  void financialOpsMustNotDependOnCustomerRepository() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("com.bancoelcapital.financialops..")
            .should()
            .dependOnClassesThat()
            .haveSimpleName("CustomerRepository");
    rule.check(classes);
  }

  @Test
  void financialOpsMustNotDependOnAuditRepositories() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("com.bancoelcapital.financialops..")
            .should()
            .dependOnClassesThat()
            .haveSimpleName("AuditEventRepository");
    rule.check(classes);
  }

  @Test
  void apiMustNotDependOnRepositories() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("com.bancoelcapital.api..")
            .should()
            .dependOnClassesThat()
            .haveSimpleNameEndingWith("Repository");
    rule.check(classes);
  }

  @Test
  void depositServiceUsesAccountsContract() {
    ArchRule rule =
        classes()
            .that()
            .haveSimpleName("DepositService")
            .should()
            .dependOnClassesThat()
            .haveSimpleName("AccountCreditService");
    rule.check(classes);
  }

  @Test
  void moduleSlicesAreFreeOfCycles() {
    ArchRule rule = slices().matching("com.bancoelcapital.(*)..").should().beFreeOfCycles();
    rule.check(classes);
  }
}
