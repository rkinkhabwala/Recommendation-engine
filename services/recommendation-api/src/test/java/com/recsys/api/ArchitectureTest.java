package com.recsys.api;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/** Hard rule #1: the serving path never calls OpenAI (or any outbound HTTP API). */
class ArchitectureTest {
  static final JavaClasses SERVING =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("com.recsys.api");

  @Test
  void servingPathDoesNotDependOnOpenAi() {
    noClasses()
        .that()
        .resideInAPackage("com.recsys.api..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("com.recsys.openai..", "com.openai..")
        .because("OpenAI must never be called synchronously on the recommendation path")
        .check(SERVING);
  }

  @Test
  void servingPathMakesNoOutboundHttpCalls() {
    noClasses()
        .that()
        .resideInAPackage("com.recsys.api..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("java.net.http..")
        .because("the hot path reads only precomputed data from Redis, Qdrant and local caches")
        .check(SERVING);
  }
}
