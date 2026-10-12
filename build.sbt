ThisBuild / scalaVersion := "2.13.18"
ThisBuild / version      := "0.1.0"
ThisBuild / organization := "io.github.alexander-hou"

val chiselVersion = "7.7.0"

lazy val root = (project in file("."))
  .settings(
    name := "rv32-cpu",

    libraryDependencies ++= Seq(
      "org.chipsalliance" %% "chisel"    % chiselVersion,
      "org.scalatest"     %% "scalatest" % "3.2.19" % Test,
    ),
    addCompilerPlugin(
      "org.chipsalliance" % "chisel-plugin" % chiselVersion cross CrossVersion.full
    ),

    scalacOptions ++= Seq(
      "-language:reflectiveCalls",
      "-Ymacro-annotations",
      "-deprecation",
      "-feature",
      "-Xcheckinit",
      "-Wunused:imports",
    ),

    Test / parallelExecution := false,
    Test / fork              := true,
    Test / javaOptions ++= Seq("-Xmx4G"),
  )
  