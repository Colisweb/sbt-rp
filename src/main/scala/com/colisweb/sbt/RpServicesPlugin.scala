package com.colisweb.sbt

import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger

import com.typesafe.sbt.packager.docker.{DockerAlias, DockerPlugin}
import com.typesafe.sbt.packager.universal.UniversalPlugin
import sbt.Keys._
import sbt.{AutoPlugin, Def, _}
import sbt.Def.Initialize
import sbt.Keys._
import sbt.std.{JoinTask, TaskExtra}

import scala.collection.mutable
import scala.language.postfixOps
import scala.sys.process.{Process, ProcessBuilder}

final case class IngressDeclaration private (
    name: String,
    services: Seq[ProjectRef],
    options: Seq[String] = Seq.empty,
    annotations: Seq[String] = Seq.empty,
    tlsSecret: Option[String] = None
)

object RpPlugin extends AutoPlugin {

  object autoImport {
    val Kubernetes = config("kubernetes")

    lazy val servicesYamlDir: TaskKey[File]                        = taskKey[File]("TODO")
    lazy val servicesYamlFile: TaskKey[File]                       = taskKey[File]("TODO")
    lazy val generateServiceResourcesOptions: TaskKey[Seq[String]] = taskKey[Seq[String]]("TODO")
    lazy val generateServiceResources: TaskKey[Unit]               = taskKey[Unit]("TODO")

    lazy val ingressesYamlDir: TaskKey[File]             = taskKey[File]("TODO")
    lazy val ingresses: TaskKey[Seq[IngressDeclaration]] = taskKey[Seq[IngressDeclaration]]("TODO")
    lazy val generateIngressResources: TaskKey[Unit]     = taskKey[Unit]("TODO")

    lazy val surchargedPublishLocal: TaskKey[Unit] = taskKey[Unit]("TODO")
  }

  import DockerPlugin.autoImport._
  import UniversalPlugin.autoImport._
  import autoImport._

  override def requires = UniversalPlugin && DockerPlugin

  private val acc: mutable.Set[DockerAlias] = mutable.Set.empty[DockerAlias]
  private val toClean: mutable.Set[File]    = mutable.Set.empty[File]
  private val serviceCount                  = new AtomicInteger(0)

  private val taskAcc: mutable.Set[Task[Any]] = mutable.Set.empty[Task[Any]]

  private def sequential(tasks: Seq[Task[Any]]): Initialize[Task[Seq[Any]]] =
    tasks.toList match {
      case Nil => Def.task { Nil }
      case x :: xs =>
        Def.taskDyn {
          val v = x.value
          sequential(xs).map(v +: _)
        }
    }

  override def projectConfigurations: Seq[Configuration] = Kubernetes :: Nil

  override lazy val projectSettings = Seq(
    generateServiceResourcesOptions := Seq(
      "--generate-pod-controllers",
      "--generate-services",
      "--registry-use-local",
      "--pod-controller-replicas 3",
      "--pod-controller-image-pull-policy Always"
    ),
    servicesYamlFile := file(s"${servicesYamlDir.value}/${name.value}.yaml"),
    generateServiceResources := {
      acc += dockerAlias.value
      toClean += servicesYamlFile.value

      serviceCount.set(ingresses.value.flatMap(_.services).size)

      val cmd =
        s"rp generate-kubernetes-resources ${dockerAlias.value.latest} ${generateServiceResourcesOptions.value.mkString(" ")}"

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

  private def totoPublishLocal = Def.taskDyn {
    val projects            = microservicesProjects.value
    val filter              = ScopeFilter(inProjects(projects: _*))
    val runningServiceTasks = (Docker / publishLocal).all(filter)

    Def.task {
      Def
        .sequential(
          runningServiceTasks,
          generateIngressResources
        )
        .value
    }
  }

  /** Projects that have the Microservice plugin enabled. */
  private lazy val microservicesProjects: Initialize[Task[Seq[ProjectRef]]] = Def.task {
    val structure = buildStructure.value
    val projects  = structure.allProjectRefs
    for {
      projRef    <- projects
      proj       <- Project.getProject(projRef, structure).toList
      autoPlugin <- proj.autoPlugins if autoPlugin == RpPlugin
    } yield projRef
  }

  private def totoDockerAlias(projectRef: Seq[ProjectRef]): Initialize[Seq[DockerAlias]] =
    dockerAlias.all(ScopeFilter(inProjects(projectRef: _*)))

  private def dontAggregate(keys: Scoped*): Seq[Setting[_]] = keys.map(aggregate in _ := false)

  private def ingress(ingresses: Seq[IngressDeclaration]): Def.Initialize[Task[Seq[ProcessBuilder]]] = {
    Initialize.join {
      ingresses.map {
        case IngressDeclaration(ingressName, services: Seq[ProjectRef], options, annotations, tlsSecret)
            if services.nonEmpty =>
          Def.task {
            val imgs = totoDockerAlias(services).value.map(_.latest)

            val cmd = Seq(
              "rp",
              "generate-kubernetes-resources",
              "--generate-ingress",
              s"--ingress-name $ingressName",
              options.mkString(" "),
              annotations.map(a => s"--ingress-annotation $a").mkString(" "),
              tlsSecret.fold("")(secretName => s"--ingress-tls-secret $secretName"),
              imgs.mkString(" ")
            ).filter(_.nonEmpty).mkString(" ")

            val log: Logger = streams.value.log

            val yamlFile = file(s"${ingressesYamlDir.value}/$ingressName.yaml")

            toClean += yamlFile

            log.info(s"RpPlugin - Executing: $cmd > ${yamlFile.getAbsolutePath}")

            Process(cmd) #> yamlFile
          }
      }
    }.flatMap(TaskExtra.joinTasks(_).join)
  }

  override def buildSettings =
    Seq(
      ingresses := Seq.empty,
      generateIngressResources := Def.taskDyn {
        val log: Logger = streams.value.log

        val v = ingresses.value
        Def.task {
          ingress(v).value.map(_ run log)
        }
      },
      surchargedPublishLocal := totoPublishLocal.value
    ) ++ dontAggregate(generateIngressResources)

}
