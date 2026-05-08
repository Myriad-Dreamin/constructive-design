import org.scalajs.jsenv.nodejs.NodeJSEnv
import org.scalajs.linker.interface.ModuleKind

ThisBuild / scalaVersion := "3.3.3"
ThisBuild / version := "0.1.0-rc1"

lazy val root = project
  .in(file("."))
  .aggregate(design, designCli, designLs)
  .settings(
    name := "constructive-design",
    publish / skip := true
  )

lazy val design = project
  .in(file("packages/design"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name := "design",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %%% "fastparse" % "3.1.1",
      "org.scalameta" %%% "munit" % "1.0.0" % Test
    )
  )

lazy val designCli = project
  .in(file("packages/design-cli"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(design)
  .settings(
    name := "design-cli",
    Compile / mainClass := Some("design.cli.Main"),
    scalaJSUseMainModuleInitializer := true,
    jsEnv := new NodeJSEnv()
  )

lazy val designLs = project
  .in(file("packages/design-ls"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(design)
  .settings(
    name := "design-ls",
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)),
    scalaJSUseMainModuleInitializer := false,
    Compile / fastLinkJS / scalaJSLinkerOutputDirectory :=
      file("editors/vscode/dist/scala/design-ls"),
    jsEnv := new NodeJSEnv()
  )
