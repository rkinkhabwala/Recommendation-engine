plugins { id("recsys.spring-boot-service") }

dependencies {
  implementation(project(":libs:common"))
  implementation(project(":libs:event-schema"))
  implementation(project(":libs:feature-store"))
  implementation("org.springframework.boot:spring-boot-starter-web") // actuator: health + prometheus
  implementation("org.springframework.kafka:spring-kafka")
  implementation("org.apache.kafka:kafka-streams")
  implementation(libs.confluent.avro.serializer)
  implementation(libs.confluent.streams.avro.serde)

  testImplementation("org.apache.kafka:kafka-streams-test-utils")
  testImplementation(testFixtures(project(":libs:feature-store")))
  testImplementation("org.testcontainers:junit-jupiter")
  testImplementation("org.testcontainers:testcontainers")
}
