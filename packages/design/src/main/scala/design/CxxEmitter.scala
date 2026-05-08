package design

object CxxEmitter {
  def emit(program: Program): String = {
    val context = Context.from(program)
    val forwardDecls = context.forwardDeclarations.map(name => s"struct $name;").mkString("\n")
    val forwardDeclBlock = if forwardDecls.isEmpty then "" else s"\n$forwardDecls\n"
    val includes =
      (List("#include <stdexcept>", "#include <string>", "#include <utility>") ++
        context.importIncludes.map(path => s"#include ${quote(path)}")).mkString("\n")
    val body = program.items.map(emitTopLevel(_, context)).mkString("\n\n")

    s"""|#pragma once
        |
        |$includes
        |
        |namespace design {
        |$forwardDeclBlock
        |$body
        |
        |} // namespace design
        |""".stripMargin
  }

  private def emitTopLevel(item: TopLevel, context: Context): String =
    item match {
      case enumDecl: EnumDecl => emitEnum(enumDecl)
      case defDecl: DefDecl => emitTopLevelDef(defDecl, context)
      case classDecl: ClassDecl => emitClass(classDecl, context)
    }

  private def emitEnum(enumDecl: EnumDecl): String = {
    val cases = enumDecl.cases.map(item => s"  ${item.name}").mkString(",\n")
    s"""|enum class ${enumDecl.name} {
        |$cases
        |};""".stripMargin
  }

  private def emitTopLevelDef(defDecl: DefDecl, context: Context): String =
    unwrapBlock(defDecl.body) match {
      case MatchExpr(scrutinee, cases) if isImplementedFunction(defDecl, scrutinee, cases) =>
        emitMatchFunction(defDecl, scrutinee, cases, context)
      case _ =>
        emitFunctionDeclaration(defDecl, context)
    }

  private def isImplementedFunction(
      defDecl: DefDecl,
      scrutinee: Expr,
      cases: List[CaseClause]
  ): Boolean =
    defDecl.returnType.exists(tpe => tpe.parts.length == 1) &&
      scrutinee.isInstanceOf[Ident] &&
      cases.nonEmpty &&
      !cases.exists(_.pattern match {
        case _: IdentPattern => true
        case _ => false
      })

  private def emitMatchFunction(
      defDecl: DefDecl,
      scrutinee: Expr,
      cases: List[CaseClause],
      context: Context
  ): String = {
    val returnType = defDecl.returnType.map(emitType(_, byValue = true, context)).getOrElse("void")
    val params = defDecl.params.map(emitParam(_, context)).mkString(", ")
    val lines = cases.map(emitMatchCase(scrutinee, _, context)).mkString("\n")

    s"""|[[nodiscard]] inline $returnType ${defDecl.name}($params) {
        |$lines
        |}""".stripMargin
  }

  private def emitMatchCase(scrutinee: Expr, clause: CaseClause, context: Context): String = {
    val condition = emitPatternCondition(scrutinee, clause.pattern, context)
    val withGuard = clause.guard match {
      case Some(guard) => s"($condition) && (${emitExpr(guard, context)})"
      case None => condition
    }
    val body = emitExpr(clause.body, context)

    clause.pattern match {
      case WildcardPattern =>
        s"  return $body;"
      case _ =>
        s"""|  if ($withGuard) {
            |    return $body;
            |  }""".stripMargin
    }
  }

  private def emitFunctionDeclaration(defDecl: DefDecl, context: Context): String = {
    val returnType = defDecl.returnType.map(emitType(_, byValue = true, context)).getOrElse("void")
    val params = defDecl.params.map(emitParam(_, context)).mkString(", ")

    s"""|// Body contains constructs that are not safely lowered to C++ yet.
        |[[nodiscard]] $returnType ${defDecl.name}($params);""".stripMargin
  }

  private def emitClass(classDecl: ClassDecl, context: Context): String = {
    val stateCases = classDecl.members.collect { case caseDecl: CaseDecl => caseDecl }
    val methods = classDecl.members.collect { case defDecl: DefDecl => defDecl }
    val initialState = stateCases
      .find(_.annotations.exists(_.name == "initial"))
      .orElse(stateCases.headOption)
      .map(_.name)
      .getOrElse("Unknown")
    val terminalStates = stateCases.filter(_.annotations.exists(_.name == "terminal"))
    val fields = classDecl.params.map(param =>
      s"  ${emitType(param.tpe, byValue = true, context)} ${fieldName(param.name)}_;"
    )
    val constructorParams =
      classDecl.params.map(param =>
        s"${emitType(param.tpe, byValue = true, context)} ${param.name}"
      )
    val initializers = classDecl.params.map(param => {
      val value =
        if isString(param.tpe) then s"std::move(${param.name})"
        else param.name
      s"${fieldName(param.name)}_($value)"
    }) :+ "state_(state)"
    val initializerList = initializers match {
      case head :: tail =>
        s"      : $head${tail.map(item => s",\n        $item").mkString} {}"
      case Nil =>
        "{}"
    }
    val hooks =
      collectHookNames(methods).map(name => s"  virtual bool $name() const { return false; }")

    s"""|class ${classDecl.name} {
        |public:
        |${indent(emitStateEnum(stateCases), 2)}
        |
        |  ${classDecl.name}(
        |${(constructorParams :+ s"State state = State::$initialState")
        .map("      " + _)
        .mkString(",\n")}
        |  )
        |$initializerList
        |
        |  [[nodiscard]] State state() const noexcept { return state_; }
        |${emitTerminalPredicate(terminalStates)}
        |
        |${indent(methods.map(emitClassMethod(_, classDecl, context)).mkString("\n\n"), 2)}
        |
        |protected:
        |${
        if hooks.isEmpty then "  // Override transition guards in generated subclasses."
        else hooks.mkString("\n")
      }
        |
        |private:
        |  [[nodiscard]] ${classDecl.name} with_state(State state) const {
        |    auto next = *this;
        |    next.state_ = state;
        |    return next;
        |  }
        |
        |${fields.mkString("\n")}
        |  State state_;
        |};""".stripMargin
  }

  private def emitStateEnum(stateCases: List[CaseDecl]): String = {
    val cases = stateCases.map(item => s"  ${item.name}").mkString(",\n")
    s"""|enum class State {
        |$cases
        |};""".stripMargin
  }

  private def emitTerminalPredicate(terminalStates: List[CaseDecl]): String =
    if terminalStates.isEmpty then ""
    else {
      val condition =
        terminalStates.map(state => s"state_ == State::${state.name}").mkString(" || ")
      s"\n  [[nodiscard]] bool is_terminal() const noexcept { return $condition; }"
    }

  private def emitClassMethod(defDecl: DefDecl, classDecl: ClassDecl, context: Context): String =
    unwrapBlock(defDecl.body) match {
      case MatchExpr(ThisExpr, cases) =>
        emitStateTransitionMethod(defDecl, classDecl, cases, context)
      case _ =>
        val returnType =
          defDecl.returnType.map(emitType(_, byValue = true, context)).getOrElse("void")
        s"[[nodiscard]] $returnType ${defDecl.name}() const;"
    }

  private def emitStateTransitionMethod(
      defDecl: DefDecl,
      classDecl: ClassDecl,
      cases: List[CaseClause],
      context: Context
  ): String = {
    val returnType =
      defDecl.returnType.map(emitType(_, byValue = true, context)).getOrElse(classDecl.name)
    val grouped = cases
      .collect { case clause @ CaseClause(IdentPattern(state), _, _) =>
        state -> clause
      }
      .groupBy(_._1)
      .view
      .mapValues(_.map(_._2))
      .toMap
    val wildcard = cases.collectFirst { case clause @ CaseClause(WildcardPattern, _, _) =>
      clause
    }
    val caseBlocks = grouped.toList.sortBy(_._1).map { case (state, clauses) =>
      val branches = clauses.map(emitTransitionBranch(_, context)).mkString("\n")
      s"""|    case State::$state:
          |$branches
          |      break;""".stripMargin
    }
    val fallback = wildcard
      .map(clause => s"    ${emitThrowStatement(clause.body, context)}")
      .getOrElse("""    throw std::runtime_error("Invalid state transition");""")

    s"""|[[nodiscard]] $returnType ${defDecl.name}() const {
        |  switch (state_) {
        |${caseBlocks.mkString("\n")}
        |  }
        |$fallback
        |}""".stripMargin
  }

  private def emitTransitionBranch(clause: CaseClause, context: Context): String = {
    val condition = clause.guard.map(emitExpr(_, context)).getOrElse("true")
    val target = emitStateTarget(clause.body, context)

    s"""|      if ($condition) {
        |        return with_state($target);
        |      }""".stripMargin
  }

  private def emitStateTarget(expr: Expr, context: Context): String =
    unwrapBlock(expr) match {
      case Ident(name) if context.classStates.contains(name) => s"State::$name"
      case _ => emitExpr(expr, context)
    }

  private def emitPatternCondition(scrutinee: Expr, pattern: Pattern, context: Context): String =
    pattern match {
      case AlternativePattern(items) =>
        items.map(emitPatternCondition(scrutinee, _, context)).map(c => s"($c)").mkString(" || ")
      case StringPattern(value) =>
        s"${emitExpr(scrutinee, context)} == ${quote(value)}"
      case NumberPattern(raw) =>
        s"${emitExpr(scrutinee, context)} == $raw"
      case BooleanPattern(value) =>
        s"${emitExpr(scrutinee, context)} == $value"
      case IdentPattern(name) =>
        s"${emitExpr(scrutinee, context)} == ${context.resolveIdentifier(name)}"
      case WildcardPattern | TodoPattern =>
        "true"
    }

  private def emitThrowStatement(expr: Expr, context: Context): String =
    expr match {
      case ThrowExpr(value) => s"throw std::runtime_error(${emitThrowable(value, context)});"
      case _ => s"throw std::runtime_error(${quote(emitExpr(expr, context))});"
    }

  private def emitExpr(expr: Expr, context: Context): String =
    unwrapBlock(expr) match {
      case Ident(name) =>
        context.resolveIdentifier(name)
      case ThisExpr =>
        "state_"
      case TodoExpr =>
        "/* TODO */"
      case StringExpr(value, _) =>
        quote(value)
      case NumberExpr(raw) =>
        raw
      case BooleanExpr(value) =>
        value.toString
      case SelectExpr(Ident(owner), field) if context.enumNames.contains(owner) =>
        s"${context.resolveImportedName(owner)}::$field"
      case SelectExpr(Ident(owner), field) if context.importedSymbols.contains(owner) =>
        s"${context.resolveImportedName(owner)}::$field"
      case SelectExpr(receiver, field) =>
        s"${emitExpr(receiver, context)}.$field"
      case CallExpr(function, args) =>
        s"${emitExpr(function, context)}(${args.map(emitExpr(_, context)).mkString(", ")})"
      case UnaryExpr(op, expr) =>
        s"$op${emitExpr(expr, context)}"
      case BinaryExpr(op, left, right) =>
        s"(${emitExpr(left, context)} ${normalizeOp(op)} ${emitExpr(right, context)})"
      case MatchExpr(_, _) =>
        "/* unsupported match expression */"
      case BlockExpr(statements) =>
        statements.map(emitExpr(_, context)).mkString("; ")
      case ThrowExpr(value) =>
        s"throw std::runtime_error(${emitThrowable(value, context)})"
      case AnnotatedExpr(_, expr) =>
        emitExpr(expr, context)
    }

  private def unwrapBlock(expr: Expr): Expr =
    expr match {
      case BlockExpr(single :: Nil) => unwrapBlock(single)
      case other => other
    }

  private def emitThrowable(expr: Expr, context: Context): String =
    expr match {
      case StringExpr(value, _) => quote(value)
      case _ => emitExpr(expr, context)
    }

  private def emitParam(param: Param, context: Context): String =
    s"${emitParamType(param.tpe, context)} ${param.name}"

  private def emitParamType(tpe: TypeRef, context: Context): String =
    if isString(tpe) then "const std::string&"
    else {
      val emitted = emitType(tpe, byValue = true, context)
      if isPrimitive(tpe) then emitted else s"const $emitted&"
    }

  private def emitType(tpe: TypeRef, byValue: Boolean, context: Context): String =
    resolveImportedType(tpe, context).mkString(".") match {
      case "String" => "std::string"
      case "Boolean" => "bool"
      case "Double" => "double"
      case "Float" => "float"
      case "Int" => "int"
      case "Long" => "long long"
      case "Unit" => "void"
      case other => other
    }

  private def resolveImportedType(tpe: TypeRef, context: Context): List[String] =
    tpe.parts match {
      case head :: tail => context.resolveImportedName(head) :: tail
      case Nil => Nil
    }

  private def collectHookNames(methods: List[DefDecl]): List[String] =
    methods.flatMap(method => collectCalls(method.body)).distinct.sorted

  private def collectCalls(expr: Expr): List[String] =
    expr match {
      case CallExpr(Ident(name), Nil) => List(name)
      case CallExpr(function, args) => collectCalls(function) ++ args.flatMap(collectCalls)
      case UnaryExpr(_, expr) => collectCalls(expr)
      case BinaryExpr(_, left, right) => collectCalls(left) ++ collectCalls(right)
      case MatchExpr(scrutinee, cases) =>
        collectCalls(scrutinee) ++ cases.flatMap(clause =>
          clause.guard.toList.flatMap(collectCalls) ++ collectCalls(clause.body)
        )
      case BlockExpr(statements) => statements.flatMap(collectCalls)
      case ThrowExpr(expr) => collectCalls(expr)
      case AnnotatedExpr(_, expr) => collectCalls(expr)
      case SelectExpr(receiver, _) => collectCalls(receiver)
      case _ => Nil
    }

  private def fieldName(name: String): String =
    if name.endsWith("_") then name.dropRight(1) else name

  private def isString(tpe: TypeRef): Boolean =
    tpe.parts == List("String")

  private def isPrimitive(tpe: TypeRef): Boolean =
    Set("Boolean", "Double", "Float", "Int", "Long").contains(tpe.parts.mkString("."))

  private def normalizeOp(op: String): String =
    op match {
      case "and" => "&&"
      case "or" => "||"
      case other => other
    }

  private def quote(value: String): String =
    "\"" + value.flatMap {
      case '\\' => "\\\\"
      case '"' => "\\\""
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case ch => ch.toString
    } + "\""

  private def indent(value: String, spaces: Int): String = {
    val prefix = " " * spaces
    value.linesIterator.map(line => if line.isEmpty then line else prefix + line).mkString("\n")
  }

  private final case class Context(
      importIncludes: List[String],
      importedSymbols: Set[String],
      importAliases: Map[String, String],
      enumNames: Set[String],
      enumCases: Map[String, String],
      classStates: Set[String],
      fields: Set[String],
      unknownTypes: Set[String]
  ) {
    def forwardDeclarations: List[String] =
      unknownTypes.toList.sorted

    def resolveIdentifier(name: String): String =
      enumCases.get(name).getOrElse {
        if classStates.contains(name) then s"State::$name"
        else if fields.contains(name) then s"${fieldName(name)}_"
        else resolveImportedName(name)
      }

    def resolveImportedName(name: String): String =
      importAliases.getOrElse(name, name)
  }

  private object Context {
    def from(program: Program): Context = {
      val enums = program.items.collect { case enumDecl: EnumDecl => enumDecl }
      val classes = program.items.collect { case classDecl: ClassDecl => classDecl }
      val importAliases = program.imports
        .flatMap(_.specifiers.map(specifier => specifier.localName -> specifier.importedName))
        .toMap
      val importedSymbols = importAliases.keySet
      val importIncludes = program.imports.map(importDecl => importHeaderPath(importDecl.path)).distinct
      val enumCases = enums
        .flatMap(enumDecl =>
          enumDecl.cases.map(caseDecl => caseDecl.name -> s"${enumDecl.name}::${caseDecl.name}")
        )
        .toMap
      val classStates = classes
        .flatMap(_.members.collect { case caseDecl: CaseDecl =>
          caseDecl.name
        })
        .toSet
      val fields = classes.flatMap(_.params.map(_.name)).toSet
      val knownTypes =
        builtInTypes ++ enums.map(_.name).toSet ++ classes.map(_.name).toSet ++ importedSymbols
      val unknownTypes = collectTypes(program).filterNot(knownTypes).toSet

      Context(
        importIncludes = importIncludes,
        importedSymbols = importedSymbols,
        importAliases = importAliases,
        enumNames = enums.map(_.name).toSet,
        enumCases = enumCases,
        classStates = classStates,
        fields = fields,
        unknownTypes = unknownTypes
      )
    }

    private val builtInTypes =
      Set("Boolean", "Double", "Float", "Int", "Long", "String", "Unit")

    private def importHeaderPath(path: String): String =
      if path.endsWith(".ds") then path.stripSuffix(".ds") + ".h"
      else if hasExtension(path) then path
      else s"$path.h"

    private def hasExtension(path: String): Boolean = {
      val fileName = path.split('/').lastOption.getOrElse(path)
      fileName.contains(".")
    }

    private def collectTypes(program: Program): List[String] =
      program.items.flatMap {
        case _: EnumDecl =>
          Nil
        case ClassDecl(_, params, members) =>
          params.map(_.tpe.parts.mkString(".")) ++ members.flatMap {
            case DefDecl(_, params, returnType, _) =>
              params.map(_.tpe.parts.mkString(".")) ++ returnType.map(_.parts.mkString(".")).toList
            case _: CaseDecl =>
              Nil
          }
        case DefDecl(_, params, returnType, _) =>
          params.map(_.tpe.parts.mkString(".")) ++ returnType.map(_.parts.mkString(".")).toList
      }
  }
}
