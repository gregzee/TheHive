package org.thp.thehive.migration.th3

import java.util.{Base64, Date}

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.Source
import akka.stream.{ActorMaterializer, Materializer}
import akka.util.ByteString
import com.google.inject.Guice
import com.sksamuel.elastic4s.http.ElasticDsl.{bool, search, termQuery}
import javax.inject.{Inject, Singleton}
import net.codingwell.scalaguice.ScalaModule
import org.elastic4play.database.{DBFind, DBGet}
import org.thp.thehive.migration
import org.thp.thehive.migration.dto._
import org.thp.thehive.models.Organisation
import org.thp.thehive.services.{ImpactStatusSrv, OrganisationSrv, ProfileSrv, ResolutionStatusSrv, UserSrv}
import play.api.inject.{ApplicationLifecycle, DefaultApplicationLifecycle}
import play.api.libs.json.{JsError, JsObject, JsSuccess, JsValue, Reads}
import play.api.{Configuration, Logger}

import scala.concurrent.ExecutionContext
import scala.reflect.{classTag, ClassTag}
import scala.util.{Failure, Success, Try}

object Input {

  def apply(configuration: Configuration)(implicit actorSystem: ActorSystem): Input =
    Guice
      .createInjector(new ScalaModule {
        override def configure(): Unit = {
          bind[Configuration].toInstance(configuration)
          bind[ActorSystem].toInstance(actorSystem)
          bind[Materializer].toInstance(ActorMaterializer())
          bind[ExecutionContext].toInstance(actorSystem.dispatcher)
          bind[ApplicationLifecycle].to[DefaultApplicationLifecycle]
          bind[Int].annotatedWithName("databaseVersion").toInstance(15)
        }
      })
      .getInstance(classOf[Input])
}

@Singleton
class Input @Inject()(configuration: Configuration, dbFind: DBFind, dbGet: DBGet, implicit val ec: ExecutionContext)
    extends migration.Input
    with Conversion {
  lazy val logger: Logger               = Logger(getClass)
  override val mainOrganisation: String = configuration.get[String]("mainOrganisation")
  implicit class SourceOfJson(source: Source[JsObject, NotUsed]) {

    def read[A: Reads: ClassTag]: Source[A, NotUsed] =
      source
        .map(_.validate[A])
        .mapConcat {
          case JsSuccess(value, _) => List(value)
          case JsError(errors) =>
            val errorStr = errors.map(e => s"\n - ${e._1}: ${e._2.mkString(",")}")
            logger.error(s"${classTag[A]} read failure:$errorStr")
            Nil
        }

    def readWithParent[A: Reads: ClassTag](parent: JsValue => Try[String]): Source[(String, A), NotUsed] =
      source
        .map(json => parent(json) -> json.validate[A])
        .mapConcat {
          case (Success(parent), JsSuccess(value, _)) => List(parent -> value)
          case (_, JsError(errors)) =>
            val errorStr = errors.map(e => s"\n - ${e._1}: ${e._2.mkString(",")}")
            logger.error(s"${classTag[A]} read failure:$errorStr")
            Nil
          case (Failure(error), _) =>
            logger.error(s"${classTag[A]} read failure", error)
            Nil
        }
  }

  def readAttachment(id: String): Source[ByteString, NotUsed] =
    Source.unfoldAsync(0) { chunkNumber =>
      dbGet("data", s"${id}_$chunkNumber")
        .map { json =>
          (json \ "binary").asOpt[String].map(s => chunkNumber + 1 -> ByteString(Base64.getDecoder.decode(s)))
        }
        .recover { case _ => None }
    }

  override def listOrganisations: Source[InputOrganisation, NotUsed] =
    Source(
      List(
        InputOrganisation(MetaData("thehive", "system", new Date, None, None), OrganisationSrv.administration),
        InputOrganisation(MetaData("thehive", "system", new Date, None, None), Organisation("thehive", "thehive"))
      )
    )

  override def listCases: Source[InputCase, NotUsed] =
    dbFind(Some("all"), Nil)(indexName => search(indexName).query(termQuery("relations", "case")))
      ._1
      .read[InputCase]

  override def listCaseObservables: Source[(String, InputObservable), NotUsed] =
    dbFind(Some("all"), Nil)(indexName => search(indexName).query(termQuery("relations", "case_artifact")))
      ._1
      .readWithParent[InputObservable](json => Try((json \ "_parent").as[String]))

  override def listCaseTasks: Source[(String, InputTask), NotUsed] =
    dbFind(Some("all"), Nil)(indexName => search(indexName).query(termQuery("relations", "case_task")))
      ._1
      .readWithParent[InputTask](json => Try((json \ "_parent").as[String]))

  override def listCaseTaskLogs: Source[(String, InputLog), NotUsed] =
    dbFind(Some("all"), Nil)(indexName => search(indexName).query(termQuery("relations", "case_task_log")))
      ._1
      .readWithParent[InputLog](json => Try((json \ "_parent").as[String]))

  override def listAlerts: Source[InputAlert, NotUsed] =
    dbFind(Some("all"), Nil)(indexName => search(indexName).query(termQuery("relations", "alert")))
      ._1
      .read[InputAlert]

  override def listAlertObservables: Source[(String, InputObservable), NotUsed] =
    dbFind(Some("all"), Nil)(indexName => search(indexName).query(termQuery("relations", "alert")))
      ._1
      .map { json =>
        for {
          metaData        <- json.validate[MetaData]
          observablesJson <- (json \ "artifacts").validate[Seq[JsValue]]
        } yield (metaData, observablesJson)
      }
      .mapConcat {
        case JsSuccess(x, _) => List(x)
        case JsError(errors) =>
          val errorStr = errors.map(e => s"\n - ${e._1}: ${e._2.mkString(",")}")
          logger.error(s"Alert observable read failure:$errorStr")
          Nil
      }
      .mapConcat {
        case (metaData, observablesJson) =>
          observablesJson.flatMap { observableJson =>
            observableJson.validate(alertObservableReads(metaData)) match {
              case JsSuccess(observable, _) => Seq(metaData.id -> observable)
              case JsError(errors) =>
                val errorStr = errors.map(e => s"\n - ${e._1}: ${e._2.mkString(",")}")
                logger.error(s"Alert observable read failure:$errorStr")
                Nil
            }
          }.toList
      }

  override def listUsers: Source[InputUser, NotUsed] =
    dbFind(Some("all"), Nil)(indexName => search(indexName).query(termQuery("relations", "user")))
      ._1
      .read[InputUser]

  override def listCustomFields: Source[InputCustomField, NotUsed] =
    dbFind(Some("all"), Nil)(
      indexName => search(indexName).query(bool(Seq(termQuery("relations", "dblist"), termQuery("dblist", "custom_fields")), Nil, Nil))
    )._1
      .read[InputCustomField]

  override def listObservableTypes: Source[InputObservableType, NotUsed] =
    dbFind(Some("all"), Nil)(
      indexName => search(indexName).query(bool(Seq(termQuery("relations", "dblist"), termQuery("dblist", "list_artifactDataType")), Nil, Nil))
    )._1
      .read[InputObservableType]

  override def listProfiles: Source[InputProfile, NotUsed] =
    Source(
      List(
        ProfileSrv.admin,
        ProfileSrv.orgAdmin,
        ProfileSrv.analyst,
        ProfileSrv.incidentHandler,
        ProfileSrv.readonly,
        ProfileSrv.all
      )
    ).map(profile => InputProfile(MetaData(profile.name, UserSrv.init.login, new Date, None, None), profile))

  override def listImpactStatus: Source[InputImpactStatus, NotUsed] =
    Source(List(ImpactStatusSrv.noImpact, ImpactStatusSrv.withImpact, ImpactStatusSrv.notApplicable))
      .map(status => InputImpactStatus(MetaData(status.value, UserSrv.init.login, new Date, None, None), status))

  override def listResolutionStatus: Source[InputResolutionStatus, NotUsed] =
    Source(
      List(
        ResolutionStatusSrv.indeterminate,
        ResolutionStatusSrv.falsePositive,
        ResolutionStatusSrv.truePositive,
        ResolutionStatusSrv.other,
        ResolutionStatusSrv.duplicated
      )
    ).map(status => InputResolutionStatus(MetaData(status.value, UserSrv.init.login, new Date, None, None), status))

  override def listCaseTemplate: Source[InputCaseTemplate, NotUsed] =
    dbFind(Some("all"), Nil)(indexName => search(indexName).query(termQuery("relations", "caseTemplate")))
      ._1
      .read[InputCaseTemplate]

  override def listCaseTemplateTask: Source[(String, InputTask), NotUsed] =
    dbFind(Some("all"), Nil)(indexName => search(indexName).query(termQuery("relations", "caseTemplate")))
      ._1
      .map { json =>
        for {
          metaData  <- json.validate[MetaData]
          tasksJson <- (json \ "tasks").validate[Seq[JsValue]]
        } yield (metaData, tasksJson)
      }
      .mapConcat {
        case JsSuccess(x, _) => List(x)
        case JsError(errors) =>
          val errorStr = errors.map(e => s"\n - ${e._1}: ${e._2.mkString(",")}")
          logger.error(s"Case template task read failure:$errorStr")
          Nil
      }
      .mapConcat {
        case (metaData, tasksJson) =>
          tasksJson.flatMap { taskJson =>
            taskJson.validate(caseTemplateTaskReads(metaData)) match {
              case JsSuccess(task, _) => Seq(metaData.id -> task)
              case JsError(errors) =>
                val errorStr = errors.map(e => s"\n - ${e._1}: ${e._2.mkString(",")}")
                logger.error(s"Case template task read failure:$errorStr")
                Nil
            }
          }.toList
      }

  override def listJobs: Source[(String, InputJob), NotUsed] =
    dbFind(Some("all"), Nil)(
      indexName => search(indexName).query(termQuery("relations", "case_artifact_job"))
    )._1
      .readWithParent[InputJob](json => Try((json \ "_parent").as[String]))(jobReads, classTag[InputJob])

  override def listJobObservables: Source[(String, InputObservable), NotUsed] =
    dbFind(Some("all"), Nil)(
      indexName => search(indexName).query(termQuery("relations", "case_artifact_job"))
    )._1
      .map { json =>
        for {
          metaData        <- json.validate[MetaData]
          observablesJson <- (json \ "artifacts").validate[Seq[JsValue]]
        } yield (metaData, observablesJson)
      }
      .mapConcat {
        case JsSuccess(x, _) => List(x)
        case JsError(errors) =>
          val errorStr = errors.map(e => s"\n - ${e._1}: ${e._2.mkString(",")}")
          logger.error(s"Case template task read failure:$errorStr")
          Nil
      }
      .mapConcat {
        case (metaData, observablesJson) =>
          observablesJson.flatMap { observableJson =>
            observableJson.validate(jobObservableReads(metaData)) match {
              case JsSuccess(observable, _) => Seq(metaData.id -> observable)
              case JsError(errors) =>
                val errorStr = errors.map(e => s"\n - ${e._1}: ${e._2.mkString(",")}")
                logger.error(s"Job observable read failure:$errorStr")
                Nil
            }
          }.toList
      }

  override def listAction: Source[(String, InputAction), NotUsed] =
    dbFind(Some("all"), Nil)(
      indexName => search(indexName).query(termQuery("relations", "action"))
    )._1
      .read[(String, InputAction)]

  override def listAudit: Source[(String, InputAudit), NotUsed] =
    dbFind(Some("all"), Nil)(
      indexName => search(indexName).query(termQuery("relations", "audit"))
    )._1.read[(String, InputAudit)]
}