plugins {
  id("recsys.java-conventions")
  `java-test-fixtures`
}

dependencies {
  api(project(":libs:common"))
  api(libs.qdrant.client)
  implementation(libs.grpc.netty.shaded)
  implementation(libs.grpc.stub)
  implementation(libs.grpc.protobuf)
  implementation("org.slf4j:slf4j-api")

  testImplementation("org.testcontainers:junit-jupiter")
  testImplementation("org.testcontainers:qdrant")
  testRuntimeOnly("ch.qos.logback:logback-classic")
}
