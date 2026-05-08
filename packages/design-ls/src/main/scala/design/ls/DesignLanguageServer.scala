package design.ls

import design.CxxEmitter
import design.DesignParser
import design.IdentifierIndex
import design.IdentifierOccurrence
import design.IdentifierSymbol
import design.IdentifierSymbolKind
import design.SourceRange

import scala.collection.mutable
import scala.scalajs.js
import scala.scalajs.js.annotation.JSExport
import scala.scalajs.js.annotation.JSExportTopLevel

@JSExportTopLevel("DesignLanguageServer")
class DesignLanguageServer {
  private val documents = mutable.Map.empty[String, DocumentState]
  private val compileToCxxCommand = "design.ls.compileToCxx"

  @JSExport
  def initialize(): js.Object = {
    val capabilities = js.Dynamic.literal(
      definitionProvider = true,
      executeCommandProvider = js.Dynamic.literal(commands = js.Array(compileToCxxCommand)),
      hoverProvider = true,
      referencesProvider = true,
      textDocumentSync = js.Dynamic.literal(
        openClose = true,
        change = 1
      )
    )
    val serverInfo = js.Dynamic.literal(name = "design-ls", version = "0.1.0")

    js.Dynamic.literal(capabilities = capabilities, serverInfo = serverInfo).asInstanceOf[js.Object]
  }

  @JSExport
  def shutdown(): Unit =
    documents.clear()

  @JSExport
  def didOpen(uri: String, text: String): js.Array[js.Object] = {
    updateDocument(uri, text)
  }

  @JSExport
  def didChange(uri: String, text: String): js.Array[js.Object] = {
    updateDocument(uri, text)
  }

  @JSExport
  def didClose(uri: String): js.Array[js.Object] = {
    documents.remove(uri)
    js.Array[js.Object]()
  }

  @JSExport
  def validate(text: String): js.Array[js.Object] =
    diagnostics(DesignParser.parseProgram(text))

  @JSExport
  def compileCxx(text: String): js.Object =
    DesignParser.parseProgram(text) match {
      case Right(program) =>
        js.Dynamic
          .literal(ok = true, content = CxxEmitter.emit(program))
          .asInstanceOf[js.Object]
      case Left(error) =>
        js.Dynamic
          .literal(ok = false, error = error)
          .asInstanceOf[js.Object]
    }

  @JSExport
  def hover(uri: String, line: Int, character: Int): js.Any =
    documents
      .get(uri)
      .flatMap { state =>
        val offset = state.offsetAt(line, character)
        state.index
          .flatMap(_.occurrenceAt(offset))
          .map(hoverObject(state, _))
      }
      .getOrElse(null)
      .asInstanceOf[js.Any]

  @JSExport
  def definition(uri: String, line: Int, character: Int): js.Any =
    documents
      .get(uri)
      .flatMap { state =>
        val offset = state.offsetAt(line, character)
        state.index
          .flatMap(_.occurrenceAt(offset))
          .flatMap(occurrence => state.index.flatMap(_.symbolFor(occurrence)))
          .filter(symbol => symbol.hasDefinition && !symbol.range.isEmpty)
          .map(symbol =>
            js.Dynamic
              .literal(uri = uri, range = state.rangeObject(symbol.range))
              .asInstanceOf[js.Object]
          )
      }
      .getOrElse(null)
      .asInstanceOf[js.Any]

  @JSExport
  def references(
      uri: String,
      line: Int,
      character: Int,
      includeDeclaration: Boolean
  ): js.Any =
    documents
      .get(uri)
      .flatMap { state =>
        val offset = state.offsetAt(line, character)
        state.index.flatMap { index =>
          index.occurrenceAt(offset).map { occurrence =>
            js.Array(
              index
                .referencesFor(occurrence, includeDeclaration)
                .map(reference =>
                  js.Dynamic
                    .literal(uri = uri, range = state.rangeObject(reference.range))
                    .asInstanceOf[js.Object]
                )*
            )
          }
        }
      }
      .getOrElse(null)
      .asInstanceOf[js.Any]

  private def updateDocument(uri: String, source: String): js.Array[js.Object] = {
    val parsed = DesignParser.parseProgram(source)
    val index = parsed.toOption.map(IdentifierIndex.from)
    documents.update(uri, DocumentState(source, index))
    diagnostics(parsed)
  }

  private def diagnostics(result: Either[String, design.Program]): js.Array[js.Object] =
    result match {
      case Right(_) =>
        js.Array[js.Object]()
      case Left(error) =>
        js.Array(
          js.Dynamic
            .literal(
              range = js.Dynamic.literal(
                start = js.Dynamic.literal(line = 0, character = 0),
                end = js.Dynamic.literal(line = 0, character = 1)
              ),
              severity = 1,
              source = "design-ls",
              message = error.take(1000)
            )
            .asInstanceOf[js.Object]
        )
    }

  private def hoverObject(state: DocumentState, occurrence: IdentifierOccurrence): js.Object = {
    val value = state.index
      .flatMap(_.symbolFor(occurrence))
      .map(hoverMarkdown)
      .getOrElse(s"identifier `${occurrence.name}`")

    js.Dynamic
      .literal(
        contents = js.Dynamic.literal(kind = "markdown", value = value),
        range = state.rangeObject(occurrence.range)
      )
      .asInstanceOf[js.Object]
  }

  private def hoverMarkdown(symbol: IdentifierSymbol): String = {
    val signature =
      s"""|```design
          |${symbol.detail}
          |```""".stripMargin
    val documentation = symbol.documentation.map(_.trim).filter(_.nonEmpty).toList
    val metadata =
      kindLabel(symbol.kind) + symbol.containerName
        .map(name => s"\n\ncontainer: `$name`")
        .getOrElse("")

    (signature :: documentation ::: List(metadata)).mkString("\n\n")
  }

  private def kindLabel(kind: IdentifierSymbolKind): String =
    kind match {
      case IdentifierSymbolKind.Import => "import"
      case IdentifierSymbolKind.Enum => "enum"
      case IdentifierSymbolKind.EnumCase => "enum case"
      case IdentifierSymbolKind.Class => "class"
      case IdentifierSymbolKind.StateCase => "state"
      case IdentifierSymbolKind.Function => "function"
      case IdentifierSymbolKind.Method => "method"
      case IdentifierSymbolKind.Parameter => "parameter"
      case IdentifierSymbolKind.Field => "field"
      case IdentifierSymbolKind.Type => "type"
      case IdentifierSymbolKind.Annotation => "annotation"
      case IdentifierSymbolKind.PatternBinding => "pattern binding"
    }

  private final case class DocumentState(source: String, index: Option[IdentifierIndex]) {
    private val lineStarts: Vector[Int] = {
      val starts = Vector.newBuilder[Int]
      starts += 0
      source.zipWithIndex.foreach { case (ch, index) =>
        if ch == '\n' then starts += index + 1
      }
      starts.result()
    }

    def offsetAt(line: Int, character: Int): Int =
      if line <= 0 && character <= 0 then 0
      else if line < 0 then 0
      else if line >= lineStarts.length then source.length
      else {
        val start = lineStarts(line)
        val end =
          if line + 1 < lineStarts.length then math.max(start, lineStarts(line + 1) - 1)
          else source.length
        math.max(start, math.min(start + math.max(character, 0), end))
      }

    def rangeObject(range: SourceRange): js.Object =
      js.Dynamic
        .literal(start = positionObject(range.start), end = positionObject(range.end))
        .asInstanceOf[js.Object]

    private def positionObject(rawOffset: Int): js.Object = {
      val offset = math.max(0, math.min(rawOffset, source.length))
      val line = math.max(0, lineStarts.lastIndexWhere(_ <= offset))
      val character = offset - lineStarts(line)
      js.Dynamic.literal(line = line, character = character).asInstanceOf[js.Object]
    }
  }
}
