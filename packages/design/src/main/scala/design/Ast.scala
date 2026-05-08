package design

trait SourceNode {
  var sourceRange: SourceRange = SourceRange.empty
  var leadingComments: List[Comment] = Nil
}

trait NamedSourceNode extends SourceNode {
  var nameRange: SourceRange = SourceRange.empty
}

enum CommentKind {
  case Line
  case Block
}

final case class Comment(kind: CommentKind, text: String, rawText: String) extends SourceNode

final case class Program(items: List[TopLevel], imports: List[ImportDecl] = Nil)
    extends SourceNode {
  var comments: List[Comment] = Nil
}

final case class ImportDecl(path: String, specifiers: List[ImportSpecifier]) extends SourceNode {
  var pathRange: SourceRange = SourceRange.empty
}

final case class ImportSpecifier(importedName: String, alias: Option[String]) extends SourceNode {
  var importedNameRange: SourceRange = SourceRange.empty
  var aliasRange: SourceRange = SourceRange.empty

  def localName: String =
    alias.getOrElse(importedName)

  def localNameRange: SourceRange =
    alias.fold(importedNameRange)(_ => aliasRange)
}

sealed trait TopLevel extends NamedSourceNode {
  def name: String
}

sealed trait Member extends SourceNode

final case class Annotation(name: String) extends NamedSourceNode

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

final case class CaseDecl(name: String, annotations: List[Annotation])
    extends Member
    with NamedSourceNode

final case class Param(name: String, tpe: TypeRef) extends NamedSourceNode

final case class TypeRef(parts: List[String]) extends SourceNode {
  var partRanges: List[SourceRange] = Nil

  override def toString: String = parts.mkString(".")
}

sealed trait Expr extends SourceNode

final case class Ident(name: String) extends Expr with NamedSourceNode
case object ThisExpr extends Expr
case object TodoExpr extends Expr
final case class StringExpr(value: String, prefix: Option[String]) extends Expr
final case class NumberExpr(raw: String) extends Expr
final case class BooleanExpr(value: Boolean) extends Expr
final case class SelectExpr(receiver: Expr, field: String) extends Expr {
  var fieldRange: SourceRange = SourceRange.empty
}
final case class CallExpr(function: Expr, args: List[Expr]) extends Expr
final case class UnaryExpr(op: String, expr: Expr) extends Expr
final case class BinaryExpr(op: String, left: Expr, right: Expr) extends Expr
final case class MatchExpr(scrutinee: Expr, cases: List[CaseClause]) extends Expr
final case class BlockExpr(statements: List[Expr]) extends Expr
final case class ThrowExpr(expr: Expr) extends Expr
final case class AnnotatedExpr(annotation: Annotation, expr: Expr) extends Expr

final case class CaseClause(pattern: Pattern, guard: Option[Expr], body: Expr) extends SourceNode

sealed trait Pattern extends SourceNode

final case class IdentPattern(name: String) extends Pattern with NamedSourceNode
case object WildcardPattern extends Pattern
case object TodoPattern extends Pattern
final case class StringPattern(value: String) extends Pattern
final case class NumberPattern(raw: String) extends Pattern
final case class BooleanPattern(value: Boolean) extends Pattern
final case class AlternativePattern(items: List[Pattern]) extends Pattern

object DesignPrinter {
  def summary(program: Program): String = {
    val importLines = program.imports.map { importDecl =>
      val names = importDecl.specifiers
        .map {
          case ImportSpecifier(importedName, Some(alias)) => s"$importedName as $alias"
          case ImportSpecifier(importedName, None) => importedName
        }
        .mkString(", ")
      s"""- import "${importDecl.path}": $names"""
    }
    val lines = importLines ++ program.items.map {
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

    val itemCount = program.imports.length + program.items.length
    s"Program($itemCount top-level items)\n${lines.mkString("\n")}"
  }
}
