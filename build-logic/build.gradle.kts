plugins { `kotlin-dsl` }

dependencies {
  implementation(libs.spring.boot.gradle.plugin)
  implementation(libs.spotless.gradle.plugin)
  implementation(libs.avro.compiler)
}

providers.gradleProperty("recsys.buildDirName").orNull?.let {
  layout.buildDirectory = layout.projectDirectory.dir(it)
}
