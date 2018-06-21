package com.colisweb.sbt

import com.typesafe.sbt.packager.docker.DockerPlugin
import com.typesafe.sbt.packager.universal.UniversalPlugin
import sbt.Keys._
import sbt.{AutoPlugin, _}

import scala.sys.process.Process

object RpPlugin extends AutoPlugin {

  object autoImport {
    lazy val yamlDir: TaskKey[File]                                = taskKey[File]("TODO")
    lazy val yamlFile: TaskKey[File]                               = taskKey[File]("TODO")
    lazy val servicesVersion: TaskKey[String]                      = taskKey[String]("TODO")
    lazy val generateServiceResourcesRepo: TaskKey[String]         = taskKey[String]("TODO")
    lazy val generateServiceResourcesOptions: TaskKey[Seq[String]] = taskKey[Seq[String]]("TODO")
    lazy val generateServiceResources: TaskKey[Unit]               = taskKey[Unit]("TODO")
  }

  import UniversalPlugin.autoImport._
  import DockerPlugin.autoImport._
  import autoImport._

  override def requires = UniversalPlugin && DockerPlugin

  final val RpConfig = config("rp-plugin").hide

  override lazy val projectSettings = Seq(
    servicesVersion := "latest",
    generateServiceResourcesOptions := Seq(
      "--generate-pod-controllers",
      "--generate-services",
      "--registry-use-local",
      "--pod-controller-replicas 3",
      "--pod-controller-image-pull-policy Always"
    ),
    yamlFile := file(s"${yamlDir.value}/${name.value}.yaml"),
    generateServiceResources := {
      val repo = s"${generateServiceResourcesRepo.value}/${name.value}:${servicesVersion.value}"
      val cmd  = s"rp generate-kubernetes-resources $repo ${generateServiceResourcesOptions.value.mkString(" ")}"

      val log: Logger = streams.value.log

      log.info(s"RpPlugin - Executing: $cmd > ${yamlFile.value}")

      Process(cmd) #> yamlFile.value run log
    },
    dist := (dist dependsOn generateServiceResources).value,
    (publish in Docker) := ((publish in Docker) dependsOn generateServiceResources).value,
    (publishLocal in Docker) := ((publishLocal in Docker) dependsOn generateServiceResources).value,
    cleanFiles += yamlFile.value
  )

}
