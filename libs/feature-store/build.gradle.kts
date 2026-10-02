plugins {
  id("recsys.java-conventions")
  `java-test-fixtures`
}

dependencies {
  api(project(":libs:common"))
  api("io.lettuce:lettuce-core")
  api("com.fasterxml.jackson.core:jackson-databind")
  implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
  implementation("org.slf4j:slf4j-api")

  testImplementation("org.testcontainers:junit-jupiter")
  testImplementation("org.testcontainers:testcontainers")
  testRuntimeOnly("ch.qos.logback:logback-classic")
}
