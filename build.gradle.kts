// Shared build logic lives in build-logic/ as convention plugins:
//   recsys.java-conventions   - Java 21 toolchain, Spring Boot BOM, JUnit 5, Spotless, integrationTest task
//   recsys.spring-boot-service - executable Spring Boot service with actuator/prometheus/tracing
//   recsys.avro                - Avro .avsc -> Java code generation
group = "com.recsys"

providers.gradleProperty("recsys.buildDirName").orNull?.let {
  layout.buildDirectory = layout.projectDirectory.dir(it)
}
