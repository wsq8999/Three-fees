package com.threefees.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.threefees", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleArchitectureTest {

  @ArchTest
  static final ArchRule DOMAIN_IS_FRAMEWORK_INDEPENDENT =
      noClasses()
          .that()
          .resideInAPackage("..domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework..",
              "org.apache.ibatis..",
              "jakarta.servlet..",
              "jakarta.persistence..");

  @ArchTest
  static final ArchRule API_DOES_NOT_REACH_MAPPERS =
      noClasses()
          .that()
          .resideInAPackage("..api..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..infrastructure.persistence..");
}
