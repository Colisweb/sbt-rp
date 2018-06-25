package com.colisweb.sbt

import com.typesafe.sbt.packager.docker.DockerPlugin
import com.typesafe.sbt.packager.universal.UniversalPlugin
import sbt.Keys._
import sbt.{AutoPlugin, Def, _}

import scala.collection.mutable
import scala.language.postfixOps
import scala.sys.process.Process

final case class IngressDeclaration private (
    name: String,
    serviceNames: Seq[String],
    options: Seq[String] = Seq.empty,
    annotations: Seq[String] = Seq.empty,
    tlsSecret: Option[String] = None
)

object IngressDeclaration {

  def build(
      name: String,
      services: Seq[Project],
      options: Seq[String] = Seq.empty,
      annotations: Seq[String] = Seq.empty,
      tlsSecret: Option[String] = None
  ): IngressDeclaration =
    new IngressDeclaration(
      name = name,
      serviceNames = services.map(_.id),
      options = options,
      annotations = annotations,
      tlsSecret = tlsSecret
    )
}

object RpPlugin extends AutoPlugin {

  object autoImport {
    lazy val registryHost: TaskKey[String]                         = taskKey[String]("Correspond to the 'registry host' in the: `docker-images - Docker images to be deployed. Format: [<registry host>/][<repo>/]image[:tag]` command option")
    lazy val repo: TaskKey[String]                                 = taskKey[String]("Correspond to the 'repo' in the: `docker-images - Docker images to be deployed. Format: [<registry host>/][<repo>/]image[:tag]` command option")
    lazy val tag: TaskKey[String]                                  = taskKey[String]("Correspond to the 'tag' in the: `docker-images - Docker images to be deployed. Format: [<registry host>/][<repo>/]image[:tag]` command option. Default value is: 'latest'")
    lazy val dockerImages: TaskKey[String]                                  = taskKey[String]("Correspond to the 'tag' in the: `docker-images - Docker images to be deployed. Format: [<registry host>/][<repo>/]image[:tag]` command option. Default value is: 'latest'")
    lazy val servicesYamlDir: TaskKey[File]                        = taskKey[File]("TODO")
    lazy val servicesYamlFile: TaskKey[File]                       = taskKey[File]("TODO")
    lazy val generateServiceResourcesOptions: TaskKey[Seq[String]] = taskKey[Seq[String]]("TODO")
    lazy val generateServiceResources: TaskKey[Unit]               = taskKey[Unit]("TODO")

    lazy val ingressesYamlDir: TaskKey[File]             = taskKey[File]("TODO")
    lazy val ingresses: TaskKey[Seq[IngressDeclaration]] = taskKey[Seq[IngressDeclaration]]("TODO")
    lazy val generateIngressResources: TaskKey[Unit]     = taskKey[Unit]("TODO")
  }

  import DockerPlugin.autoImport._
  import UniversalPlugin.autoImport._
  import autoImport._

  override def requires = UniversalPlugin && DockerPlugin

  private val acc: mutable.Set[String] = mutable.Set.empty[String]
  private val toClean : mutable.Set[File] = mutable.Set.empty[File]

  val RpServicesConfig = config("rp-plugin")

  override lazy val projectSettings = Seq(
    tag := "latest",
    generateServiceResourcesOptions := Seq(
      "--generate-pod-controllers",
      "--generate-services",
      "--registry-use-local",
      "--pod-controller-replicas 3",
      "--pod-controller-image-pull-policy Always"
    ),
    servicesYamlFile := file(s"${servicesYamlDir.value}/${name.value}.yaml"),
    dockerImages := s"${registryHost.value}/${repo.value}/${name.value}:${tag.value}",
    generateServiceResources := {
      acc += dockerImages.value
      toClean += servicesYamlFile.value

      val cmd =
        s"rp generate-kubernetes-resources ${dockerImages.value} ${generateServiceResourcesOptions.value.mkString(" ")}"

      val log: Logger = streams.value.log

      log.info(s"RpPlugin - Executing: $cmd > ${servicesYamlFile.value}")

      Process(cmd) #> servicesYamlFile.value run log
    },
    (Docker / publish) := {
      Def
        .sequential(
          Docker / publish,
          generateServiceResources
        )
        .value
    },
    (Docker / publishLocal) := {
      Def
        .sequential(
          Docker / publishLocal,
          generateServiceResources
        )
        .value
    },
    cleanFiles ++= toClean.map(_.getAbsoluteFile).toSeq
  )

  override def buildSettings = Seq(
    ingresses := Seq.empty,
    generateIngressResources := {
      ingresses.value.map {
        case IngressDeclaration(ingressName, services, options, annotations, tlsSecret) if services.nonEmpty =>
          val cmd = Seq(
            "rp",
            "generate-kubernetes-resources",
            "--generate-ingress",
            s"--ingress-name $ingressName",
            options.mkString(" "),
            annotations.map(a => s"--ingress-annotation $a").mkString(" "),
            tlsSecret.fold("")(secretName => s"--ingress-tls-secret $secretName"),
            acc.filter(repo => services.exists(repo.contains)).mkString(" ")
          ).filter(_.nonEmpty).mkString(" ")

          val log: Logger = streams.value.log

          val yamlPath = s"${ingressesYamlDir.value}/$ingressName.yaml"
          val yamlFile = file(yamlPath)

          toClean += yamlFile

          log.info(s"RpPlugin - Executing: $cmd > ${yamlFile.getAbsolutePath}")

          Process(cmd) #> yamlFile run log
      }
    }
  )

}
