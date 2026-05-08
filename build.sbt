import org.scalajs.jsenv.nodejs.NodeJSEnv

ThisBuild / scalaVersion := "3.3.3"
ThisBuild / version := "0.1.0-SNAPSHOT"

lazy val root = project
  .in(file("."))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name := "constructive-design",
    libraryDependencies += "com.lihaoyi" %%% "fastparse" % "3.1.1",
    scalaJSUseMainModuleInitializer := true,
    jsEnv := new NodeJSEnv()
  )
