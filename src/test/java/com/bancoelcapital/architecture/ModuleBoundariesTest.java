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
 * <p>F5 (api/internal encapsulation): modules expose only consumed contracts (accounts.api,
 * audit.api); internal details are package-protected. Identity stays flat (transverse contracts
 * without persistence); financialops features need no api/internal split (no external consumers).
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
  void withdrawalServiceUsesAccountsContract() {
    ArchRule rule =
        classes()
            .that()
            .haveSimpleName("WithdrawalService")
            .should()
            .dependOnClassesThat()
            .haveSimpleName("AccountDebitService");
    rule.check(classes);
  }

  @Test
  void depositClassesMustNotDependOnWithdrawalClasses() {
    ArchRule rule =
        noClasses()
            .that()
            .haveSimpleNameStartingWith("Deposit")
            .should()
            .dependOnClassesThat()
            .haveSimpleNameStartingWith("Withdrawal");
    rule.check(classes);
  }

  @Test
  void withdrawalClassesMustNotDependOnDepositClasses() {
    ArchRule rule =
        noClasses()
            .that()
            .haveSimpleNameStartingWith("Withdrawal")
            .should()
            .dependOnClassesThat()
            .haveSimpleNameStartingWith("Deposit");
    rule.check(classes);
  }

  @Test
  void coreFinancialModelMustNotDependOnDeposit() {
    ArchRule rule =
        noClasses()
            .that()
            .haveSimpleNameStartingWith("FinancialOperation")
            .or()
            .haveSimpleNameStartingWith("Movement")
            .should()
            .dependOnClassesThat()
            .haveSimpleNameStartingWith("Deposit");
    rule.check(classes);
  }

  @Test
  void coreFinancialModelMustNotDependOnWithdrawal() {
    ArchRule rule =
        noClasses()
            .that()
            .haveSimpleNameStartingWith("FinancialOperation")
            .or()
            .haveSimpleNameStartingWith("Movement")
            .should()
            .dependOnClassesThat()
            .haveSimpleNameStartingWith("Withdrawal");
    rule.check(classes);
  }

  @Test
  void financialOpsCoreMustNotDependOnDepositPackage() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("com.bancoelcapital.financialops.core..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.bancoelcapital.financialops.deposit..");
    rule.check(classes);
  }

  @Test
  void financialOpsCoreMustNotDependOnWithdrawalPackage() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("com.bancoelcapital.financialops.core..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.bancoelcapital.financialops.withdrawal..");
    rule.check(classes);
  }

  @Test
  void depositPackageMustNotDependOnWithdrawalPackage() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("com.bancoelcapital.financialops.deposit..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.bancoelcapital.financialops.withdrawal..");
    rule.check(classes);
  }

  @Test
  void withdrawalPackageMustNotDependOnDepositPackage() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("com.bancoelcapital.financialops.withdrawal..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.bancoelcapital.financialops.deposit..");
    rule.check(classes);
  }

  @Test
  void depositWebMustNotDependOnWithdrawal() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("com.bancoelcapital.financialops.deposit.web..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.bancoelcapital.financialops.withdrawal..");
    rule.check(classes);
  }

  @Test
  void accountsWebMustNotDependOnCustomers() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("com.bancoelcapital.accounts.web..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.bancoelcapital.customers..");
    rule.check(classes);
  }

  @Test
  void customersWebMustNotDependOnAccounts() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("com.bancoelcapital.customers.web..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.bancoelcapital.accounts..");
    rule.check(classes);
  }

  @Test
  void outsideAccountsMustNotDependOnAccountsInternal() {
    ArchRule rule =
        noClasses()
            .that()
            .resideOutsideOfPackage("com.bancoelcapital.accounts..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.bancoelcapital.accounts.internal..");
    rule.check(classes);
  }

  @Test
  void outsideCustomersMustNotDependOnCustomersInternal() {
    ArchRule rule =
        noClasses()
            .that()
            .resideOutsideOfPackage("com.bancoelcapital.customers..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.bancoelcapital.customers.internal..");
    rule.check(classes);
  }

  @Test
  void outsideAuditMustNotDependOnAuditInternal() {
    ArchRule rule =
        noClasses()
            .that()
            .resideOutsideOfPackage("com.bancoelcapital.audit..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.bancoelcapital.audit.internal..");
    rule.check(classes);
  }

  @Test
  void outsideAccountsWebMustNotDependOnAccountsWeb() {
    ArchRule rule =
        noClasses()
            .that()
            .resideOutsideOfPackage("com.bancoelcapital.accounts.web..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.bancoelcapital.accounts.web..");
    rule.check(classes);
  }

  @Test
  void outsideCustomersWebMustNotDependOnCustomersWeb() {
    ArchRule rule =
        noClasses()
            .that()
            .resideOutsideOfPackage("com.bancoelcapital.customers.web..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.bancoelcapital.customers.web..");
    rule.check(classes);
  }

  @Test
  void outsideDepositWebMustNotDependOnDepositWeb() {
    ArchRule rule =
        noClasses()
            .that()
            .resideOutsideOfPackage("com.bancoelcapital.financialops.deposit.web..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.bancoelcapital.financialops.deposit.web..");
    rule.check(classes);
  }

  @Test
  void outsideWithdrawalWebMustNotDependOnWithdrawalWeb() {
    ArchRule rule =
        noClasses()
            .that()
            .resideOutsideOfPackage("com.bancoelcapital.financialops.withdrawal.web..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.bancoelcapital.financialops.withdrawal.web..");
    rule.check(classes);
  }

  @Test
  void financialOpsMustNotDependOnAccountsInternal() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("com.bancoelcapital.financialops..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.bancoelcapital.accounts.internal..");
    rule.check(classes);
  }

  @Test
  void financialOpsMustNotDependOnAccountsWeb() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("com.bancoelcapital.financialops..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.bancoelcapital.accounts.web..");
    rule.check(classes);
  }

  @Test
  void moduleApisMustNotDependOnWeb() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("com.bancoelcapital..api..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.bancoelcapital..web..");
    rule.check(classes);
  }

  @Test
  void withdrawalWebMustNotDependOnDeposit() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("com.bancoelcapital.financialops.withdrawal.web..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.bancoelcapital.financialops.deposit..");
    rule.check(classes);
  }

  @Test
  void webPackagesMustNotDependOnRepositories() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("com.bancoelcapital..web..")
            .should()
            .dependOnClassesThat()
            .haveSimpleNameEndingWith("Repository");
    rule.check(classes);
  }

  @Test
  void webMustNotDependOnAccountEntity() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("com.bancoelcapital..web..")
            .should()
            .dependOnClassesThat()
            .haveSimpleName("Account");
    rule.check(classes);
  }

  @Test
  void webMustNotDependOnCustomerEntity() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("com.bancoelcapital..web..")
            .should()
            .dependOnClassesThat()
            .haveSimpleName("Customer");
    rule.check(classes);
  }

  @Test
  void webMustNotDependOnFinancialEntities() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("com.bancoelcapital..web..")
            .should()
            .dependOnClassesThat()
            .haveSimpleName("FinancialOperation");
    rule.check(classes);
    ArchRule movements =
        noClasses()
            .that()
            .resideInAPackage("com.bancoelcapital..web..")
            .should()
            .dependOnClassesThat()
            .haveSimpleName("Movement");
    movements.check(classes);
    ArchRule audit =
        noClasses()
            .that()
            .resideInAPackage("com.bancoelcapital..web..")
            .should()
            .dependOnClassesThat()
            .haveSimpleName("AuditEvent");
    audit.check(classes);
  }

  @Test
  void moduleSlicesAreFreeOfCycles() {
    ArchRule rule = slices().matching("com.bancoelcapital.(*)..").should().beFreeOfCycles();
    rule.check(classes);
  }
}
