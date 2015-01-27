resolvers += bintray.Opts.resolver.repo("embulk", "maven")

scalaVersion := "2.11.4"

name := "embulk-plugin-bio"

organization := "org.xerial.embulk"

description := "Embulk plugin for converting biological data"

crossPaths := false

incOptions := incOptions.value.withNameHashing(true)

libraryDependencies ++= Seq(
 "org.embulk" % "embulk-core" % "0.2.0",
 "org.utgenome.thirdparty" % "picard" % "1.102.0",
 "org.xerial" % "xerial-core" % "3.2.3"
)

publishMavenStyle := true

