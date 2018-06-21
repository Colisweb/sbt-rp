organization := "com.colisweb.sbt"

name := "sbt-rp"

scalaVersion := "2.12.6"

sbtPlugin := true

addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.3.4" % "provided")

credentials += Credentials(Path.userHome / ".bintray" / ".credentials")
licenses := Seq("MIT" -> url("http://opensource.org/licenses/MIT"))
homepage := Some(url("https://github.com/colisweb/sbt-rp"))
bintrayOrganization := Some("colisweb")
bintrayReleaseOnPublish := true
publishMavenStyle := true
pomExtra := (
  <url>https://github.com/colisweb/sbt-rp</url>
    <scm>
      <url>git@github.com:colisweb/sbt-rp.git</url>
      <connection>scm:git:git@github.com:colisweb/sbt-rp.git</connection>
    </scm>
    <developers>
      <developer>
        <id>guizmaii</id>
        <name>Jules Ivanic</name>
        <url>https://www.colisweb.com</url>
      </developer>
    </developers>
  )
