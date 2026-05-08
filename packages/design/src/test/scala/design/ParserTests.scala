package design

import scala.scalajs.js

class ParserTests extends munit.FunSuite {
  private val fixturePaths =
    List(
      "fixtures/syntax/top_level.ds",
      "fixtures/syntax/expressions.ds",
      "fixtures/syntax/match.ds",
      "fixtures/syntax/imports.ds",
      "syntax/design.ds"
    )

  fixturePaths.foreach { path =>
    test(s"parse $path") {
      val program = parseFixture(path)
      assert(program.items.nonEmpty)
    }
  }

  test("parse top-level declarations") {
    val program = parseFixture("fixtures/syntax/top_level.ds")
    assertEquals(program.items.map(_.name), List("Mode", "choose", "Workflow"))

    val mode = program.items(0).asInstanceOf[EnumDecl]
    assertEquals(mode.cases.map(_.name), List("cold", "warm", "hot"))
    assertEquals(mode.cases.map(_.annotations.map(_.name)), List(Nil, List("default"), Nil))

    val choose = program.items(1).asInstanceOf[DefDecl]
    assertEquals(
      choose.params.map(param => param.name -> param.tpe.parts),
      List("mode" -> List("Mode"), "fallback" -> List("Option", "Mode"))
    )
    assertEquals(choose.returnType.map(_.parts), Some(List("Result", "Value")))
    assertEquals(choose.body, Ident("mode"))

    val workflow = program.items(2).asInstanceOf[ClassDecl]
    assertEquals(
      workflow.params.map(param => param.name -> param.tpe.parts),
      List("id" -> List("String"))
    )
    assertEquals(
      workflow.members.collect { case item: CaseDecl => item.name },
      List("Idle", "Running", "Done")
    )
    assertEquals(
      workflow.members.collect { case item: CaseDecl => item.annotations.map(_.name) },
      List(List("initial"), Nil, List("terminal"))
    )
    assertEquals(workflow.members.collect { case item: DefDecl => item.name }, List("current"))
  }

  test("parse file imports") {
    val program = parseFixture("fixtures/syntax/imports.ds")

    assertEquals(program.imports.map(_.path), List("./common", "../shared/types.ds"))
    assertEquals(
      program.imports.flatMap(_.specifiers.map(specifier =>
        specifier.importedName -> specifier.alias
      )),
      List("Utils" -> None, "A" -> None, "B" -> None, "C" -> Some("D"))
    )
    assertEquals(program.items.map(_.name), List("use"))

    val use = program.items.head.asInstanceOf[DefDecl]
    assertEquals(
      use.params.map(param => param.name -> param.tpe.parts),
      List("utils" -> List("Utils"), "value" -> List("D"))
    )
    assertEquals(use.returnType.map(_.parts), Some(List("A")))
  }

  test("index imported aliases") {
    val source =
      """import "./common": C as D
        |
        |def use(value: D) -> D =
        |    D
        |""".stripMargin
    val program = DesignParser.parseProgram(source) match {
      case Right(program) => program
      case Left(error) => fail(error)
    }
    val index = IdentifierIndex.from(program)
    val symbol = index.symbols("import:./common:D")
    val occurrences = index.occurrences.filter(_.name == "D")

    assertEquals(symbol.detail, "import C as D from \"./common\"")
    assertEquals(
      occurrences.map(_.role),
      List(
        IdentifierOccurrenceRole.Definition,
        IdentifierOccurrenceRole.Reference,
        IdentifierOccurrenceRole.Reference,
        IdentifierOccurrenceRole.Reference
      )
    )
    assert(occurrences.forall(_.symbolId.contains(symbol.id)))
  }

  test("find identifier references with optional declarations") {
    val source =
      """def echo(value: String) -> String =
        |    value
        |""".stripMargin
    val program = DesignParser.parseProgram(source) match {
      case Right(program) => program
      case Left(error) => fail(error)
    }
    val index = IdentifierIndex.from(program)
    val definition = index.occurrences
      .find(occurrence =>
        occurrence.name == "value" &&
          occurrence.role == IdentifierOccurrenceRole.Definition
      )
      .getOrElse(fail("missing value definition"))
    val referencesOnly = index.referencesFor(definition, includeDeclaration = false)
    val referencesWithDeclaration = index.referencesFor(definition, includeDeclaration = true)

    assertEquals(referencesOnly.map(_.role), List(IdentifierOccurrenceRole.Reference))
    assertEquals(
      referencesWithDeclaration.map(_.role),
      List(IdentifierOccurrenceRole.Definition, IdentifierOccurrenceRole.Reference)
    )
    assert(referencesWithDeclaration.forall(_.symbolId == definition.symbolId))
  }

  test("parse expressions") {
    val program = parseFixture("fixtures/syntax/expressions.ds")
    assertEquals(program.items.map(_.name), List("arithmetic", "calls", "blocky"))

    val arithmetic = program.items(0).asInstanceOf[DefDecl]
    assertEquals(
      arithmetic.body,
      BinaryExpr(
        "-",
        BinaryExpr("+", Ident("a"), BinaryExpr("*", Ident("b"), NumberExpr("2"))),
        UnaryExpr("-", NumberExpr("1"))
      )
    )

    val calls = program.items(1).asInstanceOf[DefDecl]
    assertEquals(
      calls.body,
      CallExpr(
        SelectExpr(
          CallExpr(SelectExpr(Ident("service"), "prepare"), List(StringExpr("raw", None))),
          "run"
        ),
        List(TodoExpr, BooleanExpr(true))
      )
    )

    val blocky = program.items(2).asInstanceOf[DefDecl]
    assertEquals(
      blocky.body,
      BlockExpr(
        List(
          AnnotatedExpr(
            Annotation("trace"),
            CallExpr(SelectExpr(Ident("input"), "normalize"), Nil)
          ),
          ThrowExpr(StringExpr("bad {input}", Some("s")))
        )
      )
    )
  }

  test("parse match patterns") {
    val describe = parseFixture("fixtures/syntax/match.ds").items.head.asInstanceOf[DefDecl]
    val matchExpr = describe.body.asInstanceOf[MatchExpr]

    assertEquals(matchExpr.scrutinee, Ident("value"))
    assertEquals(matchExpr.cases.length, 4)
    assertEquals(
      matchExpr.cases.map(_.pattern),
      List(
        AlternativePattern(List(NumberPattern("0"), NumberPattern("1"))),
        IdentPattern("n"),
        TodoPattern,
        WildcardPattern
      )
    )
    assertEquals(matchExpr.cases(1).guard, Some(BinaryExpr(">", Ident("n"), NumberExpr("10"))))
    assertEquals(
      matchExpr.cases.map(_.body).last,
      ThrowExpr(StringExpr("bad {value}", Some("s")))
    )
  }

  test("format top-level function detail with keyword spacing") {
    val source =
      "def SelectCompileCommand(file_path: String, user_config: UserConfig, cdb: CompilationDatabase, include_graph: IncludeGraph) -> CompileCommand = file_path"
    val program = DesignParser.parseProgram(source) match {
      case Right(program) => program
      case Left(error) => fail(error)
    }
    val index = IdentifierIndex.from(program)

    assertEquals(
      index.symbols("function:SelectCompileCommand").detail,
      "def SelectCompileCommand(file_path: String, user_config: UserConfig, cdb: CompilationDatabase, include_graph: IncludeGraph) -> CompileCommand"
    )
  }

  test("track identifier ranges without trailing whitespace") {
    val source = "def spaced   (value   : String) -> String = value   \n"
    val defDecl = DesignParser.parseProgram(source) match {
      case Right(program) => program.items.head.asInstanceOf[DefDecl]
      case Left(error) => fail(error)
    }
    val param = defDecl.params.head
    val body = defDecl.body.asInstanceOf[Ident]

    assertEquals(source.substring(defDecl.nameRange.start, defDecl.nameRange.end), "spaced")
    assertEquals(source.substring(param.nameRange.start, param.nameRange.end), "value")
    assertEquals(source.substring(body.nameRange.start, body.nameRange.end), "value")
  }

  test("attach source comments to code nodes") {
    val source =
      """// Choose a mode
        |def choose(mode: Mode) -> Mode =
        |    mode match {
        |        /* Keep the selected value */
        |        case _ => mode
        |    }
        |""".stripMargin
    val program = parseSource(source)
    val choose = program.items.head.asInstanceOf[DefDecl]
    val clause = choose.body.asInstanceOf[MatchExpr].cases.head

    assertEquals(
      program.comments.map(_.rawText),
      List("// Choose a mode", "/* Keep the selected value */")
    )
    assertEquals(choose.leadingComments.map(_.text.trim), List("Choose a mode"))
    assertEquals(clause.leadingComments.map(_.text.trim), List("Keep the selected value"))
  }

  test("attach comments to decorated case declarations, not annotations") {
    val source =
      """class Workflow {
        |    // Starts here
        |    @initial
        |    case Idle;
        |
        |    @terminal
        |    // Ends here
        |    case Done;
        |}
        |""".stripMargin
    val workflow = parseSource(source).items.head.asInstanceOf[ClassDecl]
    val states = workflow.members.collect { case item: CaseDecl => item }
    val idle = states.head
    val done = states(1)

    assertEquals(idle.leadingComments.map(_.text.trim), List("Starts here"))
    assertEquals(idle.annotations.head.leadingComments, Nil)
    assertEquals(done.leadingComments.map(_.text.trim), List("Ends here"))
    assertEquals(done.annotations.head.leadingComments, Nil)
  }

  test("attach comments to annotated expressions, not annotations") {
    val source =
      """def run -> Unit = {
        |    @inline
        |    // Trace call
        |    runNow()
        |}
        |""".stripMargin
    val run = parseSource(source).items.head.asInstanceOf[DefDecl]
    val block = run.body.asInstanceOf[BlockExpr]
    val annotated = block.statements.head.asInstanceOf[AnnotatedExpr]

    assertEquals(annotated.leadingComments.map(_.text.trim), List("Trace call"))
    assertEquals(annotated.annotation.leadingComments, Nil)
  }

  test("index definition comments for hover documentation") {
    val source =
      """// Choose the best mode
        |def choose(mode: Mode) -> Mode =
        |    mode
        |
        |class Workflow {
        |    @terminal
        |    // Finished state
        |    case Done;
        |}
        |""".stripMargin
    val index = IdentifierIndex.from(parseSource(source))

    assertEquals(
      index.symbols("function:choose").documentation,
      Some("Choose the best mode")
    )
    assertEquals(
      index.symbols("state:Workflow.Done").documentation,
      Some("Finished state")
    )
    assertEquals(
      index.symbols("builtin:annotation:terminal").documentation,
      None
    )
  }

  private def parseSource(source: String): Program =
    DesignParser.parseProgram(source) match {
      case Right(program) => program
      case Left(error) => fail(error)
    }

  private def parseFixture(path: String): Program = {
    val source = readUtf8(path)
    DesignParser.parseProgram(source) match {
      case Right(program) => program
      case Left(error) => fail(s"failed to parse $path\n$error")
    }
  }

  private def readUtf8(path: String): String = {
    val fs = js.Dynamic.global.require("node:fs")
    fs.readFileSync(resolvePath(path), "utf8").asInstanceOf[String]
  }

  private def resolvePath(path: String): String = {
    val fs = js.Dynamic.global.require("node:fs")
    val nodePath = js.Dynamic.global.require("node:path")
    val cwd = js.Dynamic.global.process.cwd().asInstanceOf[String]
    val candidates =
      List(
        path,
        nodePath.join(cwd, path).asInstanceOf[String],
        nodePath.join(cwd, "..", "..", path).asInstanceOf[String],
        nodePath.join(cwd, "..", "..", "..", path).asInstanceOf[String]
      )

    candidates
      .find(candidate => fs.existsSync(candidate).asInstanceOf[Boolean])
      .getOrElse(fail(s"fixture not found: $path from $cwd"))
  }
}
