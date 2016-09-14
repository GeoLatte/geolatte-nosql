package controllers

import javax.inject.Inject

import config.AppExecutionContexts.streamContext
import config.Constants.{ Format, Version }
import featureserver._
import Exceptions._
import akka.stream.scaladsl.Source
import akka.util.ByteString
import play.Logger
import play.api.http.MediaType
import play.api.libs.iteratee._
import play.api.libs.json._
import play.api.libs.streams.Streams
import play.api.mvc._
import utilities.EnumeratorUtility.CommaSeparate
import utilities.SupportedMediaTypes

import scala.concurrent.Future
import scala.language.{ implicitConversions, reflectiveCalls }

/**
 * @author Karel Maesen, Geovise BVBA
 *         creation-date: 10/11/13
 */
trait AbstractFeatureServerSqlController extends Controller with FutureInstrumented {

  def repository: Repository

  def repositoryAction[T](bp: BodyParser[T])(action: Request[T] => Future[Result]) =
    Action.async(bp) { request => action(request) }

  def repositoryAction(action: Request[AnyContent] => Future[Result]): Action[AnyContent] =
    repositoryAction(BodyParsers.parse.anyContent)(action)

  implicit def toSimpleResult[A <: RenderableResource](result: A)(implicit
    request: RequestHeader,
    format: Option[Format.Value] = None,
    filename: Option[String] = None,
    version: Option[Version.Value] = None): Result = {

    implicit def toStr(js: JsObject): String = Json.stringify(js)

    val (fmt, v) = (format, version, request) match {
      case (Some(f), Some(ve), _) => (f, ve)
      case (Some(f), None, _) => (f, Version.default)
      case (_, _, SupportedMediaTypes(f, ve)) => (f, ve)
      case _ => (Format.JSON, Version.default)
    }

    def mkChunked(status: Status, mediaType: MediaType, content: Source[ByteString, _]): Result = {
      status.chunked(content).as(mediaType.toString)
    }

    val simpleresult =
      (result, fmt) match {
        case (r: Jsonable, Format.JSON) => Ok(r.toJson).as(SupportedMediaTypes(Format.JSON, v).toString)
        case (r: Csvable, Format.CSV) => Ok(r.toCsv).as(SupportedMediaTypes(Format.CSV, v).toString)
        case (r: JsonStreamable, Format.JSON) => mkChunked(Ok, SupportedMediaTypes(Format.JSON), toStream(r.toJsonStream))
        case (r: CsvStreamable, Format.CSV) => mkChunked(Ok, SupportedMediaTypes(Format.CSV, v), toStream(r.toCsvStream))
        case (r: JsonStringStreamable, Format.JSON) => mkChunked(Ok, SupportedMediaTypes(Format.JSON, v), toStream(r.toJsonStringStream))
        case _ => UnsupportedMediaType("No supported media type: " + request.acceptedTypes.mkString(";"))
      }

    filename match {
      case Some(fname) => simpleresult.withHeaders(headers = ("content-disposition", s"attachment; filename=$fname"))
      case None => simpleresult
    }
  }

  //Note: this can't be made implicit, because in the case of A == JsValue, Ok.stream accepts features enum, and
  // this code won't be called
  def toStream[A](features: Enumerator[A])(implicit toStr: A => String): Source[ByteString, _] = {

    val finalSeparatorEnumerator = Enumerator.enumerate(List(ByteString(config.Constants.chunkSeparator)))

    val startTime = System.nanoTime()

    /**
     * Adds side-effecting timer toe aan Play enumInput
     *
     * TODO -- solve by better by wrapping the futureTimed in a new Enumeratee
     *
     */
    def enumInput[E](e: Input[E]) = new Enumerator[E] {
      def apply[B](i: Iteratee[E, B]) =
        futureTimed("json-feature-enumerator", startTime) {
          i.fold {
            case Step.Cont(k) => Future(k(e))
            case _ => Future.successful(i)
          }
        }
    }

    val commaSeparate = new CommaSeparate(config.Constants.chunkSeparator)
    val jsons = features.map(f => toStr(f)) &> commaSeparate
    val toBytes = Enumeratee.map[String](ByteString(_))
    val enumerator = (jsons &> toBytes) andThen finalSeparatorEnumerator andThen enumInput(Input.EOF)
    Source.fromPublisher(Streams.enumeratorToPublisher(enumerator))
  }

  def commonExceptionHandler(db: String, col: String = ""): PartialFunction[Throwable, Result] = {
    case ex: DatabaseNotFoundException => NotFound(s"Database $db does not exist.")
    case ex: DatabaseAlreadyExistsException => Conflict(ex.getMessage)
    case ex: MediaObjectNotFoundException => NotFound(s"Media object does not exist.")
    case ex: CollectionNotFoundException => NotFound(s"Collection $db/$col does not exist.")
    case ex: IndexNotFoundException => NotFound(ex.getMessage)
    case ex: CollectionAlreadyExistsException => Conflict(ex.getMessage)
    case ex: ViewObjectNotFoundException => NotFound(s"View object does not exist.")
    case ex: InvalidParamsException => BadRequest(s"Invalid parameters: ${ex.getMessage}")
    case ex: InvalidQueryException => BadRequest(s"${ex.getMessage}")
    case ex: Throwable =>
      Logger.error(s"Internal server error with message : ${ex.getMessage}", ex)
      InternalServerError(s"Internal server error ${ex.getClass.getCanonicalName} with message : ${ex.getMessage}")
  }

}
