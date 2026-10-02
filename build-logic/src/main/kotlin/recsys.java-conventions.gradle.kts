plugins {
  `java-library`
  id("com.diffplug.spotless")
}

val catalog = the<VersionCatalogsExtension>().named("libs")

group = "com.recsys"

// Opt-in build dir override, e.g. recsys.buildDirName=build.nosync in ~/.gradle/gradle.properties
// when the checkout lives in an iCloud-synced folder (iCloud creates "file 2" conflict copies of
// rapidly rewritten files, which breaks generated-source directories).
providers.gradleProperty("recsys.buildDirName").orNull?.let {
  layout.buildDirectory = layout.projectDirectory.dir(it)
}

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

dependencies {
  val bom = platform(catalog.findLibrary("spring-boot-bom").get())
  implementation(bom)
  annotationProcessor(bom)
  testImplementation(bom)

  testImplementation("org.junit.jupiter:junit-jupiter")
  testImplementation("org.assertj:assertj-core")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

plugins.withId("java-test-fixtures") {
  dependencies { "testFixturesImplementation"(platform(catalog.findLibrary("spring-boot-bom").get())) }
}

tasks.withType<JavaCompile>().configureEach {
  options.release = 21
  options.encoding = "UTF-8"
  options.compilerArgs.addAll(listOf("-parameters", "-Xlint:deprecation", "-Xlint:unchecked"))
}

// Unit tests (*Test) run without Docker; integration tests (*IT) use Testcontainers.
tasks.test {
  useJUnitPlatform()
  exclude("**/*IT.class")
}

val integrationTest by
  tasks.registering(Test::class) {
    description = "Runs Testcontainers integration tests (*IT). Requires Docker."
    group = "verification"
    val test = sourceSets.test.get()
    testClassesDirs = test.output.classesDirs
    classpath = test.runtimeClasspath
    useJUnitPlatform()
    include("**/*IT.class")
    shouldRunAfter(tasks.test)
  }

spotless {
  java {
    target("src/*/java/**/*.java")
    googleJavaFormat() // Spotless-tested default version
  }
}

// Confluent serializers depend on Confluent's "<version>-ccs" Kafka builds, which are not on Maven
// Central; align them with the Apache Kafka version Spring Boot manages.
val kafkaVersion = catalog.findVersion("kafka").get().requiredVersion

configurations.configureEach {
  resolutionStrategy.eachDependency {
    if (requested.group == "org.apache.kafka" && requested.version?.endsWith("-ccs") == true) {
      useVersion(kafkaVersion)
      because("Confluent -ccs builds are not published to Maven Central")
    }
  }
}
