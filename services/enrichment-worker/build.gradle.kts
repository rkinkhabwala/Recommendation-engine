plugins { id("recsys.spring-boot-service") }

dependencies {
  implementation(project(":libs:common"))
  implementation(project(":libs:event-schema"))
  implementation(project(":libs:feature-store"))
  implementation(project(":libs:openai-client"))
  implementation("org.springframework.boot:spring-boot-starter-web") // actuator: health + prometheus
  implementation("org.springframework.kafka:spring-kafka")
  implementation(libs.confluent.avro.serializer)
  implementation("com.github.ben-manes.caffeine:caffeine")

  testImplementation(testFixtures(project(":libs:feature-store")))
}
