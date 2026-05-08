package design

import scala.collection.mutable

private[design] object CommentSupport {
  def attach(source: String, program: Program): Program = {
    val comments = scan(source)
    program.comments = comments

    if comments.nonEmpty then {
      val targets = attachTargets(program)
      targets.foreach(_.node.leadingComments = Nil)
      attachLeadingComments(source, comments, targets)
    }

    program
  }

  private def scan(source: String): List[Comment] = {
    val comments = mutable.ListBuffer.empty[Comment]
    var index = 0

    while index < source.length do {
      if source.startsWith("//", index) then {
        val start = index
        index += 2
        val textStart = index
        while index < source.length &&
          source.charAt(index) != '\n' &&
          source.charAt(index) != '\r'
        do index += 1
        comments += comment(CommentKind.Line, source.substring(textStart, index), start, index, source)
      } else if source.startsWith("/*", index) then {
        val start = index
        index += 2
        val textStart = index
        var depth = 1
        while index < source.length && depth > 0 do {
          if source.startsWith("/*", index) then {
            depth += 1
            index += 2
          } else if source.startsWith("*/", index) then {
            depth -= 1
            index += 2
          } else {
            index += 1
          }
        }
        val textEnd = if depth == 0 then index - 2 else index
        comments += comment(CommentKind.Block, source.substring(textStart, textEnd), start, index, source)
      } else if source.charAt(index) == '"' then {
        index = skipString(source, index)
      } else {
        index += 1
      }
    }

    comments.toList
  }

  private def comment(
      kind: CommentKind,
      text: String,
      start: Int,
      end: Int,
      source: String
  ): Comment = {
    val node = Comment(kind, text, source.substring(start, end))
    node.sourceRange = SourceRange(start, end)
    node
  }

  private def skipString(source: String, start: Int): Int = {
    var index = start + 1
    var escaped = false

    while index < source.length do {
      val ch = source.charAt(index)
      if escaped then escaped = false
      else if ch == '\\' then escaped = true
      else if ch == '"' then return index + 1
      index += 1
    }

    index
  }

  private def attachLeadingComments(
      source: String,
      comments: List[Comment],
      targets: List[AttachTarget]
  ): Unit = {
    var index = 0

    while index < comments.length do {
      val current = comments(index)
      if startsOwnLine(source, current.sourceRange.start) then {
        var end = index
        while end + 1 < comments.length &&
          startsOwnLine(source, comments(end + 1).sourceRange.start) &&
          whitespaceOnly(source, comments(end).sourceRange.end, comments(end + 1).sourceRange.start)
        do end += 1

        val group = comments.slice(index, end + 1)
        targetFor(source, group, targets).foreach { target =>
          target.node.leadingComments = target.node.leadingComments ++ group
        }
        index = end + 1
      } else {
        index += 1
      }
    }
  }

  private def targetFor(
      source: String,
      comments: List[Comment],
      targets: List[AttachTarget]
  ): Option[AttachTarget] = {
    val start = comments.head.sourceRange.start
    val end = comments.last.sourceRange.end

    targets
      .find(target => isInDecoratedPrefix(start, end, target))
      .orElse(
        targets.find(target =>
          end <= target.start && whitespaceOnly(source, end, target.start)
        )
      )
  }

  private def isInDecoratedPrefix(start: Int, end: Int, target: AttachTarget): Boolean =
    target.node match {
      case caseDecl: CaseDecl if caseDecl.annotations.nonEmpty =>
        target.start <= start && end <= target.attachStart
      case AnnotatedExpr(_, value) if !value.sourceRange.isEmpty =>
        target.start <= start && end <= target.attachStart
      case _ => false
    }

  private def attachTargets(program: Program): List[AttachTarget] = {
    val targets = mutable.ListBuffer.empty[AttachTarget]

    def add(node: SourceNode): Unit =
      if !node.sourceRange.isEmpty then {
        val target = AttachTarget(node, node.sourceRange.start, attachStart(node))
        if target.attachStart >= 0 then targets += target
      }

    def addTopLevel(item: TopLevel): Unit = {
      add(item)
      item match {
        case enumDecl: EnumDecl =>
          enumDecl.cases.foreach(add)
        case classDecl: ClassDecl =>
          classDecl.members.foreach {
            case caseDecl: CaseDecl => add(caseDecl)
            case defDecl: DefDecl =>
              add(defDecl)
              addExprTargets(defDecl.body)
          }
        case defDecl: DefDecl =>
          addExprTargets(defDecl.body)
      }
    }

    def addCaseClause(clause: CaseClause): Unit = {
      add(clause)
      clause.guard.foreach(addExprTargets)
      addExprTargets(clause.body)
    }

    def addExprTargets(expr: Expr): Unit = {
      add(expr)
      expr match {
        case SelectExpr(receiver, _) =>
          addExprTargets(receiver)
        case CallExpr(function, args) =>
          addExprTargets(function)
          args.foreach(addExprTargets)
        case UnaryExpr(_, value) =>
          addExprTargets(value)
        case BinaryExpr(_, left, right) =>
          addExprTargets(left)
          addExprTargets(right)
        case MatchExpr(scrutinee, cases) =>
          addExprTargets(scrutinee)
          cases.foreach(addCaseClause)
        case BlockExpr(statements) =>
          statements.foreach(addExprTargets)
        case ThrowExpr(value) =>
          addExprTargets(value)
        case AnnotatedExpr(_, value) =>
          addExprTargets(value)
        case _: Ident | ThisExpr | TodoExpr | _: StringExpr | _: NumberExpr | _: BooleanExpr =>
      }
    }

    program.imports.foreach(add)
    program.items.foreach(addTopLevel)
    targets.toList.sortBy(target =>
      (target.attachStart, target.start, target.node.sourceRange.end - target.start)
    )
  }

  private def attachStart(node: SourceNode): Int =
    node match {
      case named: NamedSourceNode if !named.nameRange.isEmpty => named.nameRange.start
      case AnnotatedExpr(_, value) if !value.sourceRange.isEmpty => value.sourceRange.start
      case _ => node.sourceRange.start
    }

  private def startsOwnLine(source: String, offset: Int): Boolean = {
    val lineStart = source.lastIndexOf('\n', math.max(0, offset - 1)) + 1
    var index = lineStart

    while index < offset do {
      val ch = source.charAt(index)
      if ch != ' ' && ch != '\t' && ch != '\r' then return false
      index += 1
    }

    true
  }

  private def whitespaceOnly(source: String, start: Int, end: Int): Boolean = {
    if start > end then return false

    var index = start
    while index < end do {
      if !source.charAt(index).isWhitespace then return false
      index += 1
    }

    true
  }

  private final case class AttachTarget(node: SourceNode, start: Int, attachStart: Int)
}
