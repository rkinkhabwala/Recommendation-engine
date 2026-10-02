plugins { id("recsys.spring-boot-service") }

dependencies {
  implementation(project(":libs:common"))
  implementation(project(":libs:event-schema"))
  implementation(project(":libs:feature-store"))
  implementation(project(":libs:vector-index"))
  implementation(project(":libs:web-support"))
  // NOTE: never depend on :libs:openai-client here. OpenAI is not allowed on the serving path;
  // enforced by verifyNoOpenAiOnServingPath below and by ArchitectureTest.
  implementation("org.springframework.kafka:spring-kafka")
  implementation(libs.confluent.avro.serializer)
  implementation("com.github.ben-manes.caffeine:caffeine")
  implementation(libs.resilience4j.circuitbreaker)

  testImplementation(testFixtures(project(":libs:feature-store")))
  testImplementation(testFixtures(project(":libs:vector-index")))
  testImplementation(libs.archunit.junit5)
}

val verifyNoOpenAiOnServingPath by
  tasks.registering {
    description = "Fails if the serving path can reach the OpenAI client (hard rule #1)."
    group = "verification"
    val runtime = configurations.runtimeClasspath
    doLast {
      val offenders =
        runtime.get().incoming.resolutionResult.allComponents.map { it.id.displayName }.filter {
          it.contains("openai-client") || it.contains("com.openai")
        }
      check(offenders.isEmpty()) { "OpenAI must never be on the serving path: $offenders" }
    }
  }

tasks.named("check") { dependsOn(verifyNoOpenAiOnServingPath) }
