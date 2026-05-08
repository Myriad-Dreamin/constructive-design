package design

class CxxEmitterTests extends munit.FunSuite {
  test("emit imports as includes and resolve aliases") {
    val program = parse(
      """import "./common": C as D
        |
        |def normalize(value: D) -> D = value match {
        |    case _ => D
        |}
        |""".stripMargin
    )

    val output = CxxEmitter.emit(program)

    assert(output.contains("""#include "./common.h""""))
    assert(output.contains("[[nodiscard]] inline C normalize(const C& value)"))
    assert(output.contains("  return C;"))
    assert(!output.contains("struct D;"))
  }

  test("emit ds import paths as header paths") {
    val program = parse(
      """import "../shared/types.ds": A, B
        |
        |def choose(value: B) -> A =
        |    ???
        |""".stripMargin
    )

    val output = CxxEmitter.emit(program)

    assert(output.contains("""#include "../shared/types.h""""))
    assert(output.contains("[[nodiscard]] A choose(const B& value);"))
  }

  private def parse(source: String): Program =
    DesignParser.parseProgram(source) match {
      case Right(program) => program
      case Left(error) => fail(error)
    }
}
