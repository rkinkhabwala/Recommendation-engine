plugins { id("recsys.spring-boot-service") }

dependencies {
  implementation(project(":libs:common"))
  implementation(project(":libs:event-schema"))
  implementation(project(":libs:feature-store"))
  implementation(project(":libs:openai-client"))
  implementation(project(":libs:vector-index"))
  implementation("org.springframework.boot:spring-boot-starter-web") // actuator: health + prometheus
  implementation("org.springframework.kafka:spring-kafka")
  implementation(libs.confluent.avro.serializer)

  testImplementation(testFixtures(project(":libs:vector-index")))
  testImplementation(testFixtures(project(":libs:feature-store")))
}
