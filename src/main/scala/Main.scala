//> using scala "3.3.3"
//> using platform "scala-js"
//> using jsVersion "1.16.0"
//> using dep "com.lihaoyi:fastparse_sjs1_3:3.1.1"

import des.DesParser
import des.DesPrinter

import scala.scalajs.js

object Main {
  def main(args: Array[String]): Unit = {
    val path = args.headOption.getOrElse("syntax/design.des")
    val source = readUtf8(path)

    DesParser.parseProgram(source) match {
      case Right(program) =>
        println(s"Parsed $path")
        println(DesPrinter.summary(program))
      case Left(error) =>
        println(s"Failed to parse $path")
        println(error)
        exit(1)
    }
  }

  private def readUtf8(path: String): String = {
    val fs = js.Dynamic.global.require("node:fs")
    fs.readFileSync(path, "utf8").asInstanceOf[String]
  }

  private def exit(code: Int): Unit = {
    val process = js.Dynamic.global.process
    process.exit(code)
  }
}
