plugins { id("recsys.java-conventions") }

dependencies {
  api("org.springframework.boot:spring-boot-starter-web")
  implementation("org.springframework.boot:spring-boot-autoconfigure")
  implementation(libs.nimbus.jose.jwt)
  implementation("io.micrometer:micrometer-core")

  testImplementation("org.springframework:spring-test")
}
