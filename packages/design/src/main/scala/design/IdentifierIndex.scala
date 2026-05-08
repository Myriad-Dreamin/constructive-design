package design

import scala.collection.mutable

enum IdentifierSymbolKind {
  case Import
  case Enum
  case EnumCase
  case Class
  case StateCase
  case Function
  case Method
  case Parameter
  case Field
  case Type
  case Annotation
  case PatternBinding
}

enum IdentifierOccurrenceRole {
  case Definition
  case Reference
}

final case class IdentifierSymbol(
    id: String,
    name: String,
    kind: IdentifierSymbolKind,
    range: SourceRange,
    detail: String,
    containerName: Option[String] = None,
    hasDefinition: Boolean = true,
    documentation: Option[String] = None
)

final case class IdentifierOccurrence(
    name: String,
    range: SourceRange,
    role: IdentifierOccurrenceRole,
    symbolId: Option[String]
)

final case class IdentifierIndex(
    symbols: Map[String, IdentifierSymbol],
    occurrences: List[IdentifierOccurrence]
) {
  def occurrenceAt(offset: Int): Option[IdentifierOccurrence] =
    occurrences
      .filter(_.range.contains(offset))
      .sortBy(occurrence => occurrence.range.end - occurrence.range.start)
      .headOption

  def symbolFor(occurrence: IdentifierOccurrence): Option[IdentifierSymbol] =
    occurrence.symbolId.flatMap(symbols.get)

  def referencesFor(
      occurrence: IdentifierOccurrence,
      includeDeclaration: Boolean
  ): List[IdentifierOccurrence] =
    occurrence.symbolId match {
      case Some(symbolId) =>
        occurrences.filter { candidate =>
          candidate.symbolId.contains(symbolId) &&
          (includeDeclaration || candidate.role == IdentifierOccurrenceRole.Reference)
        }
      case None => Nil
    }
}

object IdentifierIndex {
  def from(program: Program): IdentifierIndex = {
    val builder = Builder(program)
    builder.build()
  }

  private final case class Scope(values: Map[String, String], currentClass: Option[String]) {
    def withValues(additions: Map[String, String]): Scope =
      copy(values = values ++ additions)
  }

  private final class Builder(program: Program) {
    private val symbols = mutable.LinkedHashMap.empty[String, IdentifierSymbol]
    private val occurrences = mutable.ListBuffer.empty[IdentifierOccurrence]
    private val typeSymbols = mutable.LinkedHashMap.empty[String, String]
    private val valueSymbols = mutable.LinkedHashMap.empty[String, String]
    private val enumCaseSymbols = mutable.LinkedHashMap.empty[(String, String), String]
    private val classFieldSymbols = mutable.LinkedHashMap.empty[String, Map[String, String]]
    private val classMethodSymbols = mutable.LinkedHashMap.empty[String, Map[String, String]]
    private val classStateSymbols = mutable.LinkedHashMap.empty[String, Map[String, String]]
    private val defParamSymbols = mutable.LinkedHashMap.empty[String, Map[String, String]]
    private val annotationSymbols = mutable.LinkedHashMap.empty[String, String]

    def build(): IdentifierIndex = {
      defineBuiltins()
      program.imports.foreach(defineImport)
      program.items.foreach(defineTopLevel)
      program.items.foreach(referenceTopLevel)
      IdentifierIndex(symbols.toMap, occurrences.toList.sortBy(_.range.start))
    }

    private def defineBuiltins(): Unit = {
      List("Boolean", "Double", "Float", "Int", "Long", "String", "Unit").foreach { name =>
        val id = s"builtin:type:$name"
        symbols.update(
          id,
          IdentifierSymbol(
            id = id,
            name = name,
            kind = IdentifierSymbolKind.Type,
            range = SourceRange.empty,
            detail = s"built-in type $name",
            hasDefinition = false
          )
        )
        typeSymbols.update(name, id)
      }

      Map(
        "initial" -> "marks the initial state of a class state machine",
        "terminal" -> "marks a terminal state of a class state machine",
        "inline" -> "requests inline emission for a generated call"
      ).foreach { case (name, detail) =>
        val id = s"builtin:annotation:$name"
        symbols.update(
          id,
          IdentifierSymbol(
            id = id,
            name = name,
            kind = IdentifierSymbolKind.Annotation,
            range = SourceRange.empty,
            detail = s"@$name: $detail",
            hasDefinition = false
          )
        )
        annotationSymbols.update(name, id)
      }
    }

    private def defineImport(importDecl: ImportDecl): Unit =
      importDecl.specifiers.foreach { specifier =>
        val id = s"import:${importDecl.path}:${specifier.localName}"
        symbols.update(
          id,
          IdentifierSymbol(
            id = id,
            name = specifier.localName,
            kind = IdentifierSymbolKind.Import,
            range = specifier.localNameRange,
            detail = importDetail(importDecl, specifier),
            containerName = Some(importDecl.path),
            documentation = documentation(importDecl)
          )
        )
        specifier.alias match {
          case Some(_) =>
            addOccurrence(
              specifier.importedName,
              specifier.importedNameRange,
              IdentifierOccurrenceRole.Reference,
              None
            )
            addOccurrence(
              specifier.localName,
              specifier.localNameRange,
              IdentifierOccurrenceRole.Definition,
              Some(id)
            )
          case None =>
            addOccurrence(
              specifier.importedName,
              specifier.importedNameRange,
              IdentifierOccurrenceRole.Definition,
              Some(id)
            )
        }
        valueSymbols.update(specifier.localName, id)
        typeSymbols.update(specifier.localName, id)
      }

    private def defineTopLevel(item: TopLevel): Unit =
      item match {
        case enumDecl: EnumDecl =>
          val id = s"enum:${enumDecl.name}"
          defineNamed(
            id,
            enumDecl.name,
            IdentifierSymbolKind.Enum,
            enumDecl.nameRange,
            s"enum ${enumDecl.name}",
            None,
            documentation(enumDecl)
          )
          typeSymbols.update(enumDecl.name, id)

          enumDecl.cases.foreach { caseDecl =>
            val caseId = s"enum-case:${enumDecl.name}.${caseDecl.name}"
            defineNamed(
              caseId,
              caseDecl.name,
              IdentifierSymbolKind.EnumCase,
              caseDecl.nameRange,
              s"case ${caseDecl.name}",
              Some(enumDecl.name),
              documentation(caseDecl)
            )
            enumCaseSymbols.update((enumDecl.name, caseDecl.name), caseId)
            valueSymbols.update(caseDecl.name, caseId)
          }

        case classDecl: ClassDecl =>
          val id = s"class:${classDecl.name}"
          defineNamed(
            id,
            classDecl.name,
            IdentifierSymbolKind.Class,
            classDecl.nameRange,
            s"class ${classDecl.name}",
            None,
            documentation(classDecl)
          )
          typeSymbols.update(classDecl.name, id)

          val fields = classDecl.params.map { param =>
            val fieldId = s"field:${classDecl.name}.${param.name}"
            defineNamed(
              fieldId,
              param.name,
              IdentifierSymbolKind.Field,
              param.nameRange,
              s"field ${classDecl.name}.${param.name}: ${param.tpe}",
              Some(classDecl.name),
              documentation(param)
            )
            param.name -> fieldId
          }.toMap
          classFieldSymbols.update(classDecl.name, fields)

          val states = classDecl.members.collect { case caseDecl: CaseDecl =>
            val stateId = s"state:${classDecl.name}.${caseDecl.name}"
            defineNamed(
              stateId,
              caseDecl.name,
              IdentifierSymbolKind.StateCase,
              caseDecl.nameRange,
              s"case ${caseDecl.name}",
              Some(classDecl.name),
              documentation(caseDecl)
            )
            caseDecl.name -> stateId
          }.toMap
          classStateSymbols.update(classDecl.name, states)

          val methods = classDecl.members.collect { case defDecl: DefDecl =>
            defDecl.name -> defineDef(defDecl, Some(classDecl.name))
          }.toMap
          classMethodSymbols.update(classDecl.name, methods)

        case defDecl: DefDecl =>
          defineDef(defDecl, None)
      }

    private def defineDef(defDecl: DefDecl, owner: Option[String]): String = {
      val id = owner match {
        case Some(className) => s"method:$className.${defDecl.name}"
        case None => s"function:${defDecl.name}"
      }
      val kind = owner match {
        case Some(_) => IdentifierSymbolKind.Method
        case None => IdentifierSymbolKind.Function
      }
      val params = defDecl.params.map(param => s"${param.name}: ${param.tpe}").mkString(", ")
      val result = defDecl.returnType.fold("Unit")(_.toString)
      val prefix = owner.fold("def ")(className => s"def $className.")
      defineNamed(
        id,
        defDecl.name,
        kind,
        defDecl.nameRange,
        s"$prefix${defDecl.name}($params) -> $result",
        owner,
        documentation(defDecl)
      )
      if owner.isEmpty then valueSymbols.update(defDecl.name, id)

      val paramSymbols = defDecl.params.map { param =>
        val paramId = s"param:$id.${param.name}"
        defineNamed(
          paramId,
          param.name,
          IdentifierSymbolKind.Parameter,
          param.nameRange,
          s"param ${param.name}: ${param.tpe}",
          Some(defDecl.name),
          documentation(param)
        )
        param.name -> paramId
      }.toMap
      defParamSymbols.update(id, paramSymbols)
      id
    }

    private def defineNamed(
        id: String,
        name: String,
        kind: IdentifierSymbolKind,
        range: SourceRange,
        detail: String,
        containerName: Option[String],
        documentation: Option[String] = None
    ): Unit = {
      symbols.update(
        id,
        IdentifierSymbol(
          id = id,
          name = name,
          kind = kind,
          range = range,
          detail = detail,
          containerName = containerName,
          documentation = documentation
        )
      )
      addOccurrence(name, range, IdentifierOccurrenceRole.Definition, Some(id))
    }

    private def documentation(node: SourceNode): Option[String] = {
      val text = node.leadingComments.flatMap(commentLines).mkString("\n").trim
      Option.when(text.nonEmpty)(text)
    }

    private def commentLines(comment: Comment): List[String] =
      comment.kind match {
        case CommentKind.Line =>
          List(comment.text.trim)
        case CommentKind.Block =>
          trimBlankLines(comment.text.linesIterator.map(cleanBlockCommentLine).toList)
      }

    private def cleanBlockCommentLine(line: String): String = {
      val trimmed = line.trim
      if trimmed.startsWith("*") then trimmed.drop(1).trim else trimmed
    }

    private def trimBlankLines(lines: List[String]): List[String] =
      lines.dropWhile(_.isEmpty).reverse.dropWhile(_.isEmpty).reverse

    private def referenceTopLevel(item: TopLevel): Unit =
      item match {
        case enumDecl: EnumDecl =>
          enumDecl.cases.foreach(referenceCaseDeclAnnotations)

        case classDecl: ClassDecl =>
          classDecl.params.foreach(param => referenceType(param.tpe))
          classDecl.members.foreach {
            case caseDecl: CaseDecl =>
              referenceCaseDeclAnnotations(caseDecl)
            case defDecl: DefDecl =>
              referenceDef(defDecl, Some(classDecl.name))
          }

        case defDecl: DefDecl =>
          referenceDef(defDecl, None)
      }

    private def referenceCaseDeclAnnotations(caseDecl: CaseDecl): Unit =
      caseDecl.annotations.foreach(referenceAnnotation)

    private def referenceDef(defDecl: DefDecl, owner: Option[String]): Unit = {
      val id = owner match {
        case Some(className) => s"method:$className.${defDecl.name}"
        case None => s"function:${defDecl.name}"
      }
      defDecl.params.foreach(param => referenceType(param.tpe))
      defDecl.returnType.foreach(referenceType)

      val localValues = defParamSymbols.getOrElse(id, Map.empty)
      val classValues = owner match {
        case Some(className) =>
          classFieldSymbols.getOrElse(className, Map.empty) ++
            classMethodSymbols.getOrElse(className, Map.empty) ++
            classStateSymbols.getOrElse(className, Map.empty)
        case None => Map.empty[String, String]
      }
      val scope = Scope(valueSymbols.toMap ++ classValues ++ localValues, owner)
      referenceExpr(defDecl.body, scope)
    }

    private def referenceAnnotation(annotation: Annotation): Unit =
      addOccurrence(
        annotation.name,
        annotation.nameRange,
        IdentifierOccurrenceRole.Reference,
        annotationSymbols.get(annotation.name)
      )

    private def referenceType(tpe: TypeRef): Unit =
      tpe.parts.zipAll(tpe.partRanges, "", SourceRange.empty).foreach { case (name, range) =>
        val symbolId = typeSymbols.get(name)
        addOccurrence(name, range, IdentifierOccurrenceRole.Reference, symbolId)
      }

    private def referenceExpr(expr: Expr, scope: Scope): Unit =
      expr match {
        case ident: Ident =>
          addOccurrence(
            ident.name,
            ident.nameRange,
            IdentifierOccurrenceRole.Reference,
            resolveValueOrType(ident.name, scope)
          )

        case select @ SelectExpr(receiver, field) =>
          referenceExpr(receiver, scope)
          addOccurrence(
            field,
            select.fieldRange,
            IdentifierOccurrenceRole.Reference,
            resolveSelect(receiver, field, scope)
          )

        case CallExpr(function, args) =>
          referenceExpr(function, scope)
          args.foreach(referenceExpr(_, scope))

        case UnaryExpr(_, value) =>
          referenceExpr(value, scope)

        case BinaryExpr(_, left, right) =>
          referenceExpr(left, scope)
          referenceExpr(right, scope)

        case MatchExpr(scrutinee, cases) =>
          referenceExpr(scrutinee, scope)
          cases.foreach(referenceCaseClause(_, scope))

        case BlockExpr(statements) =>
          statements.foreach(referenceExpr(_, scope))

        case ThrowExpr(value) =>
          referenceExpr(value, scope)

        case AnnotatedExpr(annotation, value) =>
          referenceAnnotation(annotation)
          referenceExpr(value, scope)

        case ThisExpr | TodoExpr | StringExpr(_, _) | NumberExpr(_) | BooleanExpr(_) => ()
      }

    private def referenceCaseClause(clause: CaseClause, scope: Scope): Unit = {
      val boundValues = mutable.LinkedHashMap.empty[String, String]
      referencePattern(clause.pattern, scope, boundValues)
      val caseScope = scope.withValues(boundValues.toMap)
      clause.guard.foreach(referenceExpr(_, caseScope))
      referenceExpr(clause.body, caseScope)
    }

    private def referencePattern(
        pattern: Pattern,
        scope: Scope,
        boundValues: mutable.LinkedHashMap[String, String]
    ): Unit =
      pattern match {
        case ident: IdentPattern =>
          resolvePattern(ident.name, scope) match {
            case Some(symbolId) =>
              addOccurrence(
                ident.name,
                ident.nameRange,
                IdentifierOccurrenceRole.Reference,
                Some(symbolId)
              )
            case None =>
              val id = s"binding:${ident.nameRange.start}:${ident.name}"
              symbols.update(
                id,
                IdentifierSymbol(
                  id = id,
                  name = ident.name,
                  kind = IdentifierSymbolKind.PatternBinding,
                  range = ident.nameRange,
                  detail = s"pattern ${ident.name}",
                  containerName = None
                )
              )
              boundValues.update(ident.name, id)
              addOccurrence(
                ident.name,
                ident.nameRange,
                IdentifierOccurrenceRole.Definition,
                Some(id)
              )
          }

        case AlternativePattern(items) =>
          items.foreach(referencePattern(_, scope, boundValues))

        case WildcardPattern | TodoPattern | StringPattern(_) | NumberPattern(_) |
            BooleanPattern(_) =>
          ()
      }

    private def resolveValueOrType(name: String, scope: Scope): Option[String] =
      scope.values.get(name).orElse(typeSymbols.get(name))

    private def resolvePattern(name: String, scope: Scope): Option[String] =
      scope.currentClass
        .flatMap(className => classStateSymbols.getOrElse(className, Map.empty).get(name))
        .orElse(valueSymbols.get(name))

    private def resolveSelect(receiver: Expr, field: String, scope: Scope): Option[String] =
      receiver match {
        case Ident(owner) if isEnum(owner) =>
          enumCaseSymbols.get((owner, field))
        case ThisExpr =>
          scope.currentClass.flatMap { className =>
            classFieldSymbols
              .getOrElse(className, Map.empty)
              .get(field)
              .orElse(classStateSymbols.getOrElse(className, Map.empty).get(field))
          }
        case _ => None
      }

    private def isEnum(name: String): Boolean =
      typeSymbols
        .get(name)
        .flatMap(symbols.get)
        .exists(_.kind == IdentifierSymbolKind.Enum)

    private def importDetail(importDecl: ImportDecl, specifier: ImportSpecifier): String =
      specifier.alias match {
        case Some(alias) =>
          s"import ${specifier.importedName} as $alias from \"${importDecl.path}\""
        case None =>
          s"import ${specifier.importedName} from \"${importDecl.path}\""
      }

    private def addOccurrence(
        name: String,
        range: SourceRange,
        role: IdentifierOccurrenceRole,
        symbolId: Option[String]
    ): Unit =
      if !range.isEmpty then occurrences.append(IdentifierOccurrence(name, range, role, symbolId))
  }
}
