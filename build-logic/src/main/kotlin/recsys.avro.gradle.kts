import org.apache.avro.Schema
import org.apache.avro.compiler.specific.SpecificCompiler
import org.apache.avro.generic.GenericData

plugins { id("recsys.java-conventions") }

/**
 * Generates SpecificRecord classes from src/main/avro. Files under `common/` are parsed first so
 * shared named types (e.g. Domain) can be referenced by full name from other schemas.
 */
abstract class GenerateAvroTask : DefaultTask() {
  @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val source: DirectoryProperty

  @get:OutputDirectory abstract val output: DirectoryProperty

  @TaskAction
  fun generate() {
    val out = output.get().asFile
    out.deleteRecursively()
    out.mkdirs()
    val root = source.get().asFile
    val files =
      root
        .walkTopDown()
        .filter { it.isFile && it.extension == "avsc" }
        .sortedWith(compareBy({ !it.path.contains("${File.separator}common${File.separator}") }, { it.name }))
        .toList()
    val parser = Schema.Parser()
    files.forEach { file ->
      val schema = parser.parse(file)
      val compiler = SpecificCompiler(schema)
      compiler.setStringType(GenericData.StringType.String)
      compiler.setFieldVisibility(SpecificCompiler.FieldVisibility.PRIVATE)
      compiler.setOutputCharacterEncoding("UTF-8")
      compiler.compileToDestination(file, out)
    }
  }
}

val generateAvro by
  tasks.registering(GenerateAvroTask::class) {
    source = layout.projectDirectory.dir("src/main/avro")
    output = layout.buildDirectory.dir("generated/sources/avro/main")
  }

sourceSets.main { java.srcDir(generateAvro) }
