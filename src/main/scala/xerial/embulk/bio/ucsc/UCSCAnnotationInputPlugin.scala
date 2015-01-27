package xerial.embulk.bio.ucsc

import java.io.{FileNotFoundException, StringReader, InputStreamReader}
import java.net.URL
import java.util.zip.GZIPInputStream
import javax.validation.constraints.NotNull

import com.fasterxml.jackson.annotation.JacksonInject
import com.google.common.collect.ImmutableList
import org.embulk.config._
import org.embulk.spi.InputPlugin.Control
import org.embulk.spi._
import java.{util => ju}
import java.{lang => jl}

import org.embulk.spi.`type`.{DoubleType, StringType, LongType, Type}
import xerial.core.io.IOUtil

/**
 *
 */
class UCSCAnnotationInputPlugin extends InputPlugin {

  trait PluginTask extends Task {
    @Config("annotations")
    @NotNull
    def annotations: ju.List[String]

    @Config("refseq")
    @NotNull
    def refseq: String

    @JacksonInject
    def getBufferAllocator(): BufferAllocator
  }

  private def withResource[In <: AutoCloseable, U](in:In)(body: In => U): U = {
    try {
      body(in)
    }
    finally {
      in.close()
    }
  }

  implicit class Regex(sc: StringContext) {
    def r = new scala.util.matching.Regex(sc.parts.mkString, sc.parts.tail.map(_ => "x"): _*)
  }

  private def download(url:String) : String = {
    withResource(new URL(url).openStream()) { in =>
      IOUtil.readFully(in) { content =>
        new String(content)
      }
    }
  }

  protected def resolveType(typeName:String) : Type = {
    typeName.toLowerCase match {
      case "tinyint" | "smallint" | "bigint" | "int" => new LongType
      case "float" | "double" | "decimal" => new DoubleType
      case "char" | "varchar" | "blob" | "text" | "longtext" | "longblob"  => new StringType
      case _ => new StringType
    }
  }

  protected def buildSchema(createStmt:String): Schema = {

    val columnList = ImmutableList.builder[Column]

    val remaining = createStmt.lines.dropWhile(s => !s.toLowerCase.contains("create table"))
    remaining.next()
    var index = 0
    var toContinue = true
    while(toContinue && remaining.hasNext) {
      val line = remaining.next()
      //  e.g., `bin` smallint(5) unsigned NOT NULL,
      line match {
        case r"\s+'(\w+)${colName}'\s+(\w+)${typeName}.*\sNOT NULL.*" =>
          columnList.add(new Column(index, colName, resolveType(typeName)))
          index += 1
        case _ => toContinue = false
      }
    }
    new Schema(columnList.build())
  }


  override def transaction(config: ConfigSource, control: Control): NextConfig = {
    val task = config.loadConfig(classOf[PluginTask])

    val urlPrefix = s"http://hgdownload.cse.ucsc.edu/goldenPath/${task.refseq}/database"

    for(a <- task.annotations) {
      // Download schema file
      val sqlUrl = s"${urlPrefix}/${a}.sql"
      val createTableStmt = download(sqlUrl)

      // Create a schema of the file
      val schema = buildSchema(createTableStmt)

      // Run input task
      val source = task.dump()
      source.set("url", s"${urlPrefix}/${a}.txt.gz")
      control.run(task.dump, schema, 1)
    }
    Exec.newNextConfig()
  }

  override def run(taskSource: TaskSource, schema: Schema, processorIndex: Int, output: PageOutput): CommitReport = {

    val dataUrl = taskSource.get(classOf[String], "url")

    val pageBuilder = new PageBuilder(Exec.getBufferAllocator, schema, output)
    val report = Exec.newCommitReport()
    try {
      // Download .txt.gz data
      for (line <- scala.io.Source.fromInputStream(new GZIPInputStream(new URL(dataUrl).openStream())).getLines()) {
        val cols = line.split("\t")
        for ((c, i) <- cols.zipWithIndex) {
          try {
            // Write record
            schema.visitColumns(new SchemaVisitor {
              override def doubleColumn(column: Column): Unit = pageBuilder.setDouble(i, c.toDouble)
              override def timestampColumn(column: Column): Unit = pageBuilder.setTimestamp(i, null) // TODO
              override def booleanColumn(column: Column): Unit = pageBuilder.setBoolean(i, c.toBoolean)
              override def longColumn(column: Column): Unit = pageBuilder.setLong(i, c.toLong)
              override def stringColumn(column: Column): Unit = pageBuilder.setString(i, c)
            })
          }
          catch {
            // Set null if some error happens
            case e: IllegalArgumentException =>
              pageBuilder.setNull(i)
            case e: NumberFormatException =>
              pageBuilder.setNull(i)
          }
          pageBuilder.addRecord()
        }
      }
    }
    catch {
      case e: FileNotFoundException =>
        report.set("error", e)
    }
    finally {
      pageBuilder.finish()
    }

    report
  }

}
