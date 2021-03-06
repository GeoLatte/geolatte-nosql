package controllers

import javax.inject.Inject
import config.AppExecutionContexts
import config.Constants.Format
import metrics.Instrumentation
import persistence.Repository
import utilities.Utils.Logger
import play.api.libs.json._
import play.api.mvc.{ InjectedController, PlayBodyParsers, Result }
import utilities.JsonHelper

import scala.concurrent.Future

class ViewController @Inject() (val repository: Repository, val instrumentation: Instrumentation, val parsers: PlayBodyParsers)
  extends InjectedController
  with RepositoryAction
  with ExceptionHandlers {

  import AppExecutionContexts.streamContext
  import Formats._

  object QueryParams {
    val NAME = QueryParam("name", (s) => Some(s))
  }

  def put(db: String, collection: String, viewName: String) = withRepository(parsers.tolerantJson) {
    implicit req =>
      {
        req.body.validate(ViewDefIn) match {
          case JsError(er) =>
            Logger.warn(er.mkString("\n"))
            Future.successful(BadRequest("Invalid view definition: " + er.mkString("\n")))

          case JsSuccess(js, _) =>
            repository.saveView(db, collection, js ++ Json.obj("name" -> viewName))
              .map[Result](updatedExisting =>
                if (updatedExisting) Ok
                else Created.withHeaders("Location" -> controllers.routes.ViewController.get(db, collection, viewName).url)).recover(commonExceptionHandler(db, collection))
        }
      }
  }

  def list(db: String, collection: String) = withRepository {
    implicit req =>
      {
        QueryParams.NAME.value.map(name =>
          repository.getView(db, collection, name)
            .map(viewDef2Result(db, collection))).orElse {
          Some(
            repository.getViews(db, collection)
              .map(res => res.map(el => format(db, collection)(el)))
              .map(res => Ok(JsArray(res)).as(SupportedMediaTypes(Format.JSON).toString()))
          )
        }.get.recover(commonExceptionHandler(db, collection))
      }
  }

  def get(db: String, collection: String, id: String) = withRepository {
    implicit req =>
      {
        repository.getView(db, collection, id)
          .map(viewDef2Result(db, collection))
          .recover(commonExceptionHandler(db, collection))
      }
  }

  def delete(db: String, collection: String, view: String) = withRepository {
    implicit req =>
      {
        repository.dropView(db, collection, view).map(v => {
          Logger.info(s"Result of drop of view: $view: $v")
          Ok("View dropped")
        })
          .recover(commonExceptionHandler(db, collection))
      }
  }

  private def format(db: String, col: String)(viewDef: JsObject): JsObject = {
    Logger.debug("RECEIVED: " + viewDef)
    val outFormat = ViewDefOut(db, col)
    viewDef.validate(outFormat) match {
      case JsSuccess(out, _) => out
      case JsError(err) =>
        throw new RuntimeException(
          s"Error when formatting view definition: ${JsonHelper.JsValidationErrors2String(err)}"
        )
    }
  }

  private def viewDef2Result(db: String, col: String)(viewDef: JsObject): Result =
    Ok(format(db: String, col: String)(viewDef)).as(SupportedMediaTypes(Format.JSON).toString())
}
