name := "proj4j"

organization := "com.azavea"

version := "1.0"

autoScalaLibrary := false

crossPaths := false

libraryDependencies ++= Seq(
  "com.opencsv" % "opencsv" % "3.7",
  "org.scalatest" %% "scalatest" % "2.2.6" % "test",
  "org.scalacheck"      %% "scalacheck"      % "1.13.0" % "test")
