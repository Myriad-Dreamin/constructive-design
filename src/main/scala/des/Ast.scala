package des

final case class Program(items: List[TopLevel])

sealed trait TopLevel {
  def name: String
}

sealed trait Member

final case class Annotation(name: String)

final case class EnumDecl(name: String, cases: List[CaseDecl]) extends TopLevel

final case class ClassDecl(name: String, params: List[Param], members: List[Member])
    extends TopLevel

final case class DefDecl(
    name: String,
    params: List[Param],
    returnType: Option[TypeRef],
    body: Expr
) extends TopLevel
    with Member

final case class CaseDecl(name: String, annotations: List[Annotation]) extends Member

final case class Param(name: String, tpe: TypeRef)

final case class TypeRef(parts: List[String]) {
  override def toString: String = parts.mkString(".")
}

sealed trait Expr

final case class Ident(name: String) extends Expr
case object ThisExpr extends Expr
case object TodoExpr extends Expr
final case class StringExpr(value: String, prefix: Option[String]) extends Expr
final case class NumberExpr(raw: String) extends Expr
final case class BooleanExpr(value: Boolean) extends Expr
final case class SelectExpr(receiver: Expr, field: String) extends Expr
final case class CallExpr(function: Expr, args: List[Expr]) extends Expr
final case class UnaryExpr(op: String, expr: Expr) extends Expr
final case class BinaryExpr(op: String, left: Expr, right: Expr) extends Expr
final case class MatchExpr(scrutinee: Expr, cases: List[CaseClause]) extends Expr
final case class BlockExpr(statements: List[Expr]) extends Expr
final case class ThrowExpr(expr: Expr) extends Expr
final case class AnnotatedExpr(annotation: Annotation, expr: Expr) extends Expr

final case class CaseClause(pattern: Pattern, guard: Option[Expr], body: Expr)

sealed trait Pattern

final case class IdentPattern(name: String) extends Pattern
case object WildcardPattern extends Pattern
case object TodoPattern extends Pattern
final case class StringPattern(value: String) extends Pattern
final case class NumberPattern(raw: String) extends Pattern
final case class BooleanPattern(value: Boolean) extends Pattern
final case class AlternativePattern(items: List[Pattern]) extends Pattern

object DesPrinter {
  def summary(program: Program): String = {
    val lines = program.items.map {
      case EnumDecl(name, cases) =>
        s"- enum $name: ${cases.length} cases"
      case DefDecl(name, params, returnType, _) =>
        val result = returnType.fold("Unit")(_.toString)
        s"- def $name: ${params.length} params -> $result"
      case ClassDecl(name, params, members) =>
        val states = members.collect { case caseDecl: CaseDecl => caseDecl }
        val defs = members.collect { case defDecl: DefDecl => defDecl }
        s"- class $name: ${params.length} params, ${states.length} states, ${defs.length} defs"
    }

    s"Program(${program.items.length} top-level items)\n${lines.mkString("\n")}"
  }
}
