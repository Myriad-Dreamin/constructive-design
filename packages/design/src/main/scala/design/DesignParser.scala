package design

import fastparse.*
import fastparse.ScalaWhitespace.*

object DesignParser {
  private final case class LocatedName(value: String, range: SourceRange)
  private final case class LocatedString(value: String, range: SourceRange)
  private final case class LocatedArgs(values: List[Expr], range: SourceRange)

  def parseProgram(source: String): Either[String, Program] =
    fastparse.parse(source, program(_)) match {
      case Parsed.Success(value, _) => Right(CommentSupport.attach(source, value))
      case failure: Parsed.Failure => Left(failure.trace().longMsg)
    }

  def program[$: P]: P[Program] =
    P(Index ~ ("" ~ programItem).rep ~ Index ~ End).map { case (start, parsedItems, end) =>
      val items = parsedItems.collect { case ProgramItem.Declaration(value) => value }.toList
      val imports = parsedItems.collect { case ProgramItem.Import(value) => value }.toList
      withSourceRange(Program(items, imports), SourceRange(start, end))
    }

  private def programItem[$: P]: P[ProgramItem] =
    P(importDecl.map(ProgramItem.Import.apply) | topLevel.map(ProgramItem.Declaration.apply))

  def topLevel[$: P]: P[TopLevel] =
    P(enumDecl | classDecl | defDecl)

  def importDecl[$: P]: P[ImportDecl] =
    withSourceRange(
      P(keyword("import") ~/ locatedImportPath ~ ":" ~/ importSpecifier.rep(1, sep = ","))
        .map { case (path, specifiers) =>
          val node = ImportDecl(path.value, specifiers.toList)
          node.pathRange = path.range
          node
        } ~ P(";").?
    )

  def importSpecifier[$: P]: P[ImportSpecifier] =
    P(locatedIdentifier ~ (keyword("as") ~/ locatedIdentifier).?).map {
      case (importedName, alias) =>
        val node = ImportSpecifier(importedName.value, alias.map(_.value))
        node.importedNameRange = importedName.range
        alias.foreach(value => node.aliasRange = value.range)
        node.sourceRange = SourceRange.span(List(node.importedNameRange, node.aliasRange))
        node
    }

  def enumDecl[$: P]: P[EnumDecl] =
    withSourceRange(
      P(keyword("enum") ~/ locatedIdentifier ~ "{" ~ enumCase.rep ~ "}").map { case (name, cases) =>
        withNameRange(EnumDecl(name.value, cases.toList), name)
      }
    )

  def classDecl[$: P]: P[ClassDecl] =
    withSourceRange(
      P(keyword("class") ~/ locatedIdentifier ~ paramList.? ~ "{" ~ classMember.rep ~ "}")
        .map { case (name, params, members) =>
          withNameRange(ClassDecl(name.value, params.getOrElse(Nil), members.toList), name)
        }
    )

  def classMember[$: P]: P[Member] =
    P(enumCase | defDecl)

  def annotations[$: P]: P[List[Annotation]] =
    P(annotation.rep).map(_.toList)

  def enumCase[$: P]: P[CaseDecl] =
    withSourceRange(
      P(
        (annotations ~ keyword("case") ~/ locatedIdentifier)
          .map { case (annotations, name) =>
            withNameRange(CaseDecl(name.value, annotations), name)
          } ~ P(";").?
      )
    )

  def defDecl[$: P]: P[DefDecl] =
    withSourceRange(
      P(keyword("def") ~/ locatedIdentifier ~ paramList.? ~ returnType.? ~ "=" ~/ expr).map {
        case (name, params, returnType, body) =>
          withNameRange(DefDecl(name.value, params.getOrElse(Nil), returnType, body), name)
      }
    )

  def annotation[$: P]: P[Annotation] =
    withSourceRange(
      P("@" ~/ locatedIdentifier).map(name => withNameRange(Annotation(name.value), name))
    )

  def paramList[$: P]: P[List[Param]] =
    P("(" ~/ param.rep(sep = ",") ~ ")").map(_.toList)

  def param[$: P]: P[Param] =
    withSourceRange(
      P(locatedIdentifier ~ ":" ~/ typeRef).map { case (name, tpe) =>
        withNameRange(Param(name.value, tpe), name)
      }
    )

  def returnType[$: P]: P[TypeRef] =
    P(("->" | ":") ~/ typeRef)

  def typeRef[$: P]: P[TypeRef] =
    P(locatedIdentifier.rep(1, sep = ".")).map { parts =>
      val tokens = parts.toList
      val node = TypeRef(tokens.map(_.value))
      node.partRanges = tokens.map(_.range)
      node.sourceRange = SourceRange.span(node.partRanges)
      node
    }

  def expr[$: P]: P[Expr] =
    P(annotatedExpr | throwExpr | matchExpr)

  def annotatedExpr[$: P]: P[Expr] =
    withSourceRange(P(annotation ~ expr).map(AnnotatedExpr.apply.tupled))

  def throwExpr[$: P]: P[Expr] =
    withSourceRange(P(keyword("throw") ~/ expr).map(ThrowExpr.apply))

  def matchExpr[$: P]: P[Expr] =
    withSourceRange(
      P(logical ~ (keyword("match") ~/ caseBlock).?).map {
        case (scrutinee, Some(cases)) => MatchExpr(scrutinee, cases)
        case (scrutinee, None) => scrutinee
      }
    )

  def caseBlock[$: P]: P[List[CaseClause]] =
    P("{" ~/ caseClause.rep ~ "}").map(_.toList)

  def caseClause[$: P]: P[CaseClause] =
    withSourceRange(
      P(keyword("case") ~/ pattern ~ (keyword("if") ~/ expr).? ~ "=>" ~/ expr).map {
        case (pattern, guard, body) => CaseClause(pattern, guard, body)
      }
    )

  def pattern[$: P]: P[Pattern] =
    P(patternAtom.rep(1, sep = "|")).map { items =>
      val patterns = items.toList
      patterns match {
        case single :: Nil => single
        case many => AlternativePattern(many)
      }
    }

  def patternAtom[$: P]: P[Pattern] =
    P(
      "_".map(_ => WildcardPattern)
        | todo.map(_ => TodoPattern)
        | stringLiteral.map(value => StringPattern(value.value))
        | numberLiteral.map(value => NumberPattern(value.raw))
        | booleanLiteral.map(value => BooleanPattern(value.value))
        | locatedIdentifier.map(name => withNameRange(IdentPattern(name.value), name))
    )

  def logical[$: P]: P[Expr] =
    binary(comparison, logicalOp)

  def comparison[$: P]: P[Expr] =
    binary(additive, comparisonOp)

  def additive[$: P]: P[Expr] =
    binary(multiplicative, additiveOp)

  def multiplicative[$: P]: P[Expr] =
    binary(unary, multiplicativeOp)

  def unary[$: P]: P[Expr] =
    P(unaryOp.! ~/ unary).map(UnaryExpr.apply.tupled) | postfix

  def postfix[$: P]: P[Expr] =
    P(atom ~ postfixStep.rep).map { case (base, steps) =>
      steps.foldLeft(base) {
        case (expr, PostfixStep.Select(name, range)) =>
          val node = SelectExpr(expr, name)
          node.fieldRange = range
          node.sourceRange = SourceRange.span(List(expr.sourceRange, range))
          node
        case (expr, PostfixStep.Call(args, range)) =>
          val node = CallExpr(expr, args)
          node.sourceRange = SourceRange.span(List(expr.sourceRange, range))
          node
      }
    }

  private def postfixStep[$: P]: P[PostfixStep] =
    P(
      ("." ~/ locatedIdentifier).map(name => PostfixStep.Select(name.value, name.range)) |
        locatedArgList.map(args => PostfixStep.Call(args.values, args.range))
    )

  def atom[$: P]: P[Expr] =
    P(
      todo
        | stringLiteral
        | numberLiteral
        | booleanLiteral
        | keyword("this").map(_ => ThisExpr)
        | locatedIdentifier.map(name => withNameRange(Ident(name.value), name))
        | parens
        | block
    )

  def parens[$: P]: P[Expr] =
    P("(" ~/ expr ~ ")")

  def block[$: P]: P[Expr] =
    withSourceRange(
      P("{" ~/ expr.rep(sep = ";") ~ ";".? ~ "}").map(values => BlockExpr(values.toList))
    )

  def argList[$: P]: P[List[Expr]] =
    locatedArgList.map(_.values)

  private def locatedArgList[$: P]: P[LocatedArgs] =
    P(Index ~ "(" ~/ expr.rep(sep = ",") ~ ")" ~ Index).map { case (start, values, end) =>
      LocatedArgs(values.toList, SourceRange(start, end))
    }

  def todo[$: P]: P[Expr] =
    P("???").map(_ => TodoExpr)

  def stringLiteral[$: P]: P[StringExpr] =
    P((("s".! ~~ &("\"")).?).map(_.getOrElse("")) ~~ "\"" ~/ stringChar.rep.! ~ "\"")
      .map { case (prefix, value) => StringExpr(value, Option.when(prefix.nonEmpty)(prefix)) }

  private def locatedImportPath[$: P]: P[LocatedString] =
    P(Index ~ "\"" ~/ stringChar.rep.! ~ "\"" ~ Index).map { case (start, value, end) =>
      LocatedString(value, SourceRange(start, end))
    }

  def stringChar[$: P]: P[Unit] =
    P("\\" ~ AnyChar | CharsWhile(ch => ch != '"' && ch != '\\' && ch != '\n'))

  def numberLiteral[$: P]: P[NumberExpr] =
    P(CharIn("0-9").rep(1) ~~ ("." ~~ CharIn("0-9").rep(1)).?).!.map(NumberExpr.apply)

  def booleanLiteral[$: P]: P[BooleanExpr] =
    P(keyword("true").map(_ => BooleanExpr(true)) | keyword("false").map(_ => BooleanExpr(false)))

  def binary[$: P](next: => P[Expr], op: => P[String]): P[Expr] =
    P(next ~ (op ~/ next).rep).map { case (left, rest) =>
      rest.foldLeft(left) { case (current, (op, right)) => BinaryExpr(op, current, right) }
    }

  def logicalOp[$: P]: P[String] =
    P("&&" | "||" | ("and" ~~ !identifierContinue) | ("or" ~~ !identifierContinue)).!

  def comparisonOp[$: P]: P[String] =
    P(">=" | "<=" | "==" | "!=" | ">" | "<").!

  def additiveOp[$: P]: P[String] =
    P(CharIn("+\\-")).!

  def multiplicativeOp[$: P]: P[String] =
    P(CharIn("*/%")).!

  def unaryOp[$: P]: P[Unit] =
    P("!" | "-" | "+")

  def keyword[$: P](value: String): P[Unit] =
    P(value ~~ !identifierContinue)

  def identifier[$: P]: P[String] =
    locatedIdentifier.map(_.value)

  private def locatedIdentifier[$: P]: P[LocatedName] =
    P(Index ~ identifierText ~~ Index).map { case (start, name, end) =>
      LocatedName(name, SourceRange(start, end))
    }

  private def identifierText[$: P]: P[String] =
    P((identifierStart ~~ identifierContinue.repX).!).filter(!keywords.contains(_))

  def identifierStart[$: P]: P[Unit] =
    P(CharIn("a-zA-Z_"))

  def identifierContinue[$: P]: P[Unit] =
    P(CharIn("a-zA-Z0-9_"))

  private enum PostfixStep {
    case Select(name: String, range: SourceRange)
    case Call(args: List[Expr], range: SourceRange)
  }

  private enum ProgramItem {
    case Import(value: ImportDecl)
    case Declaration(value: TopLevel)
  }

  private def withSourceRange[T <: SourceNode, $: P](parser: => P[T]): P[T] =
    P(Index ~ parser ~ Index).map { case (start, node, end) =>
      withSourceRange(node, SourceRange(start, end))
    }

  private def withSourceRange[T <: SourceNode](node: T, range: SourceRange): T = {
    node.sourceRange = range
    node
  }

  private def withNameRange[T <: NamedSourceNode](node: T, name: LocatedName): T = {
    node.nameRange = name.range
    if node.sourceRange.isEmpty then node.sourceRange = name.range
    node
  }

  private val keywords =
    Set(
      "and",
      "as",
      "case",
      "class",
      "def",
      "else",
      "enum",
      "false",
      "if",
      "import",
      "match",
      "or",
      "this",
      "throw",
      "true"
    )
}
