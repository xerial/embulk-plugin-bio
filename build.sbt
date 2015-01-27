resolvers += bintray.Opts.resolver.repo("embulk", "maven")

scalaVersion := "2.11.4"

name := "embulk-plugin-sam"

organization := "org.xerial.embulk"

description := "Embulk plugin for converting SAM format to BAM"

crossPaths := false

incOptions := incOptions.value.withNameHashing(true)

libraryDependencies ++= Seq(
 "org.embulk" % "embulk-core" % "0.2.0",
 "org.utgenome.thirdparty" % "picard" % "1.102.0"
)

publishMavenStyle := true



