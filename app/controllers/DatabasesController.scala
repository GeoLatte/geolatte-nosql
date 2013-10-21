package controllers

import play.api.mvc._
import play.api.libs.json.{JsValue, JsError, JsNull}
import scala.concurrent.Future
import play.Logger
import scala.Some
import nosql.Exceptions._
import nosql.mongodb.{Metadata, MongoRepository}


/**
 * @author Karel Maesen, Geovise BVBA
 *         creation-date: 7/22/13
 */
object DatabasesController extends AbstractNoSqlController {

  import config.AppExecutionContexts.streamContext;


  def list() = {
    Action {
      implicit request =>
        Async {
          MongoRepository.listDatabases.map( dbs => toResult(DatabasesResource(dbs)) )
            .recover { case ex =>
                Logger.error(s"Couldn't list databases : ${ex.getMessage}")
                InternalServerError(ex.getMessage) }
        }
    }
  }

  def getDb(db: String) = Action {
    implicit request =>
      val fc = MongoRepository.listCollections(db)
       Async {
            fc.map ( colls => {
              Logger.info("collections found: " + colls)
              toResult(DatabaseResource(db, colls))
            } ).recover (commonExceptionHandler(db))
          }
       }


  def createDb(db: String) = Action {
    implicit request =>

      Async {
        MongoRepository.createDb(db).map(_ => Created(s"database $db created") ).recover{
          case ex : DatabaseAlreadyExists => Conflict(ex.getMessage)
          case ex : DatabaseCreationException => InternalServerError(ex.getMessage)
          case t => InternalServerError(s"${t.getMessage}")
        }
      }
  }

  def deleteDb(db: String) = Action {
    implicit request =>
      Async{
        MongoRepository.deleteDb(db).map( _ => Ok(s"database $db dropped") )
          .recover (commonExceptionHandler(db))
      }
  }

  def getCollection(db: String, collection: String) = Action {
    implicit request =>
      Async{
        MongoRepository.metadata(db, collection).map(md => {Logger.info(s"medata: $md") ;toResult(CollectionResource(md))} )
          .recover(commonExceptionHandler(db,collection))
      }
  }

  def createCollection(db: String, col: String) = Action(BodyParsers.parse.tolerantJson) {
    implicit request => {

      def parse(body: JsValue) = body match {
        case JsNull => Right(None)
        case js: JsValue => js.validate(CollectionResource.Reads).fold(
          invalid = errs => Left(JsError.toFlatJson(errs)),
          valid = v => Right(Some(v)))
      }

      def doCreate(spatialSpecOpt: Option[Metadata]) = {
        MongoRepository.createCollection(db, col, spatialSpecOpt).map(_ => Ok(s"$db/$col ")).recover {
          case ex: DatabaseNotFoundException => NotFound(s"No database $db")
          case ex: CollectionAlreadyExists => Conflict(s"Collection $db/$col already exists.")
          case ex: Throwable => InternalServerError(s"{ex.getMessage}")
        }
      }

      Async {
        parse(request.body) match {
          case Right(spatialSpecOpt) => doCreate(spatialSpecOpt)
          case Left(errs) => Future.successful(BadRequest("Invalid JSON format: " + errs))
        }

      }
    }
  }

  def deleteCollection(db: String, col: String) = Action {
    implicit request =>
      Async {
        MongoRepository.deleteCollection(db, col).map(_ => Ok(s"Collection $db/$col deleted."))
          .recover(commonExceptionHandler(db,col))
      }
  }

}
