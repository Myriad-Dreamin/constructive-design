package design.cli

import design.CxxEmitter
import design.DesignParser

import scala.scalajs.js

object Main {
  def main(args: Array[String]): Unit = {
    val inputPath = args.headOption.getOrElse("syntax/design.ds")
    val outputPath = args.drop(1).headOption.getOrElse("examples/cxx/include/design.h")
    val source = readUtf8(inputPath)

    DesignParser.parseProgram(source) match {
      case Right(program) =>
        writeUtf8(outputPath, CxxEmitter.emit(program))
        println(s"Generated $outputPath from $inputPath")
      case Left(error) =>
        println(s"Failed to parse $inputPath")
        println(error)
        exit(1)
    }
  }

  private def readUtf8(path: String): String = {
    val fs = js.Dynamic.global.require("node:fs")
    fs.readFileSync(path, "utf8").asInstanceOf[String]
  }

  private def writeUtf8(path: String, content: String): Unit = {
    val fs = js.Dynamic.global.require("node:fs")
    val nodePath = js.Dynamic.global.require("node:path")
    fs.mkdirSync(nodePath.dirname(path), js.Dynamic.literal(recursive = true))
    fs.writeFileSync(path, content, "utf8")
  }

  private def exit(code: Int): Unit = {
    val process = js.Dynamic.global.process
    process.exit(code)
  }
}
