package des

import fastparse.*
import fastparse.ScalaWhitespace.*

object DesParser {
  def parseProgram(source: String): Either[String, Program] =
    fastparse.parse(source, program(_)) match {
      case Parsed.Success(value, _) => Right(value)
      case failure: Parsed.Failure => Left(failure.trace().longMsg)
    }

  def program[$: P]: P[Program] =
    P(("" ~ topLevel).rep ~ End).map(items => Program(items.toList))

  def topLevel[$: P]: P[TopLevel] =
    P(enumDecl | classDecl | defDecl)

  def enumDecl[$: P]: P[EnumDecl] =
    P(keyword("enum") ~/ identifier ~ "{" ~ enumCase.rep ~ "}").map { case (name, cases) =>
      EnumDecl(name, cases.toList)
    }

  def classDecl[$: P]: P[ClassDecl] =
    P(keyword("class") ~/ identifier ~ paramList.? ~ "{" ~ classMember.rep ~ "}").map {
      case (name, params, members) => ClassDecl(name, params.getOrElse(Nil), members.toList)
    }

  def classMember[$: P]: P[Member] =
    P(enumCase | defDecl)

  def annotations[$: P]: P[List[Annotation]] =
    P(annotation.rep).map(_.toList)

  def enumCase[$: P]: P[CaseDecl] =
    P(
      (annotations ~ keyword("case") ~/ identifier)
        .map((annotations, name) => CaseDecl(name, annotations)) ~ P(";").?
    )

  def defDecl[$: P]: P[DefDecl] =
    P(keyword("def") ~/ identifier ~ paramList.? ~ returnType.? ~ "=" ~/ expr).map {
      case (name, params, returnType, body) =>
        DefDecl(name, params.getOrElse(Nil), returnType, body)
    }

  def annotation[$: P]: P[Annotation] =
    P("@" ~/ identifier).map(Annotation.apply)

  def paramList[$: P]: P[List[Param]] =
    P("(" ~/ param.rep(sep = ",") ~ ")").map(_.toList)

  def param[$: P]: P[Param] =
    P(identifier ~ ":" ~/ typeRef).map(Param.apply.tupled)

  def returnType[$: P]: P[TypeRef] =
    P(("->" | ":") ~/ typeRef)

  def typeRef[$: P]: P[TypeRef] =
    P(identifier.rep(1, sep = ".")).map(parts => TypeRef(parts.toList))

  def expr[$: P]: P[Expr] =
    P(annotatedExpr | throwExpr | matchExpr)

  def annotatedExpr[$: P]: P[Expr] =
    P(annotation ~ expr).map(AnnotatedExpr.apply.tupled)

  def throwExpr[$: P]: P[Expr] =
    P(keyword("throw") ~/ expr).map(ThrowExpr.apply)

  def matchExpr[$: P]: P[Expr] =
    P(logical ~ (keyword("match") ~/ caseBlock).?).map {
      case (scrutinee, Some(cases)) => MatchExpr(scrutinee, cases)
      case (scrutinee, None) => scrutinee
    }

  def caseBlock[$: P]: P[List[CaseClause]] =
    P("{" ~/ caseClause.rep ~ "}").map(_.toList)

  def caseClause[$: P]: P[CaseClause] =
    P(keyword("case") ~/ pattern ~ (keyword("if") ~/ expr).? ~ "=>" ~/ expr).map {
      case (pattern, guard, body) => CaseClause(pattern, guard, body)
    }

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
        | identifier.map(IdentPattern.apply)
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
        case (expr, PostfixStep.Select(name)) => SelectExpr(expr, name)
        case (expr, PostfixStep.Call(args)) => CallExpr(expr, args)
      }
    }

  private def postfixStep[$: P]: P[PostfixStep] =
    P(("." ~/ identifier).map(PostfixStep.Select.apply) | argList.map(PostfixStep.Call.apply))

  def atom[$: P]: P[Expr] =
    P(
      todo
        | stringLiteral
        | numberLiteral
        | booleanLiteral
        | keyword("this").map(_ => ThisExpr)
        | identifier.map(Ident.apply)
        | parens
        | block
    )

  def parens[$: P]: P[Expr] =
    P("(" ~/ expr ~ ")")

  def block[$: P]: P[Expr] =
    P("{" ~/ expr.rep(sep = ";") ~ ";".? ~ "}").map(values => BlockExpr(values.toList))

  def argList[$: P]: P[List[Expr]] =
    P("(" ~/ expr.rep(sep = ",") ~ ")").map(_.toList)

  def todo[$: P]: P[Expr] =
    P("???").map(_ => TodoExpr)

  def stringLiteral[$: P]: P[StringExpr] =
    P((("s".! ~~ &("\"")).?).map(_.getOrElse("")) ~~ "\"" ~/ stringChar.rep.! ~ "\"")
      .map { case (prefix, value) => StringExpr(value, Option.when(prefix.nonEmpty)(prefix)) }

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
    P((identifierStart ~~ identifierContinue.repX).!).filter(!keywords.contains(_))

  def identifierStart[$: P]: P[Unit] =
    P(CharIn("a-zA-Z_"))

  def identifierContinue[$: P]: P[Unit] =
    P(CharIn("a-zA-Z0-9_"))

  private enum PostfixStep {
    case Select(name: String)
    case Call(args: List[Expr])
  }

  private val keywords =
    Set(
      "and",
      "case",
      "class",
      "def",
      "else",
      "enum",
      "false",
      "if",
      "match",
      "or",
      "this",
      "throw",
      "true"
    )
}
