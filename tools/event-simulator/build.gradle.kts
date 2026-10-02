plugins {
  id("recsys.java-conventions")
  application
}

dependencies {
  implementation("com.fasterxml.jackson.core:jackson-databind")
  implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
}

application {
  mainClass = "com.recsys.sim.Main"
  applicationName = "event-simulator"
}
