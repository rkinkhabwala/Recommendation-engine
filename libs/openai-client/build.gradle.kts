plugins { id("recsys.java-conventions") }

dependencies {
  api(project(":libs:common"))
  api("io.micrometer:micrometer-core")
  implementation("com.fasterxml.jackson.core:jackson-databind")
  implementation(libs.resilience4j.retry)
  implementation(libs.resilience4j.circuitbreaker)
  implementation(libs.resilience4j.ratelimiter)
  implementation("org.slf4j:slf4j-api")

  testRuntimeOnly("ch.qos.logback:logback-classic")
}
