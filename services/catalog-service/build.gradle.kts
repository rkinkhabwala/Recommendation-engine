plugins { id("recsys.spring-boot-service") }

dependencies {
  implementation(project(":libs:common"))
  implementation(project(":libs:event-schema"))
  implementation(project(":libs:web-support"))
  implementation("org.springframework.boot:spring-boot-starter-jdbc")
  implementation("org.flywaydb:flyway-core")
  implementation("org.flywaydb:flyway-database-postgresql")
  runtimeOnly("org.postgresql:postgresql")
  implementation("org.springframework.kafka:spring-kafka")
  implementation(libs.confluent.avro.serializer)

  testImplementation("org.testcontainers:junit-jupiter")
  testImplementation("org.testcontainers:postgresql")
  testImplementation("org.postgresql:postgresql")
}
