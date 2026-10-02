plugins { id("recsys.spring-boot-service") }

dependencies {
  implementation(project(":libs:common"))
  implementation(project(":libs:event-schema"))
  implementation(project(":libs:web-support"))
  implementation("org.springframework.boot:spring-boot-starter-validation")
  implementation("org.springframework.kafka:spring-kafka")
  implementation(libs.confluent.avro.serializer)
  implementation("com.github.ben-manes.caffeine:caffeine")

  testImplementation("org.testcontainers:junit-jupiter")
  testImplementation("org.testcontainers:kafka")
}
