package nosql.postgresql

import nosql.{JsonUtils, Metadata}
import play.api.libs.iteratee.Enumerator
import play.api.libs.json.{JsObject, Reads}
import JsonUtils._

import scala.concurrent.ExecutionContext.Implicits.global

/**
  * Created by Karel Maesen, Geovise BVBA on 15/04/16.
  */
trait QueryResultEnumerator {

  def enumerate(qr: Any, optProj : Option[Reads[JsObject]]) : Enumerator[JsObject]

}



abstract class TableQueryResultEnumerator(md: Metadata) extends QueryResultEnumerator {
  require(md.pkey != null && md.geometryColumn != null, "This enumerator requires known primary keys and geometry columns")

//  override def enumerate(qr: QueryResult, optProj: Option[Reads[JsObject]]): Enumerator[JsObject] =
//    qr.rows match {
//      case Some(rows) => enumerate (rows, optProj)
//      case None => Enumerator.empty
//    }
//
//  private def enumerate(resultSet: ResultSet, optProj: Option[Reads[JsObject]]) : Enumerator[JsObject] = {
//    val colnames = resultSet.columnNames
//    val mapF = QueryResultUtils.toMap(colnames) _
//
//    val project : JsObject => JsObject = optProj match {
//       case Some(reads) => js => js.as(reads)
//       case _ => identity
//     }
//
//    val geojsons = for {
//      row <- resultSet
//      map = mapF(row)
//      json = JsonUtils.toGeoJson(md.pkey, md.geometryColumn, map)
//      projected = project(json)
//    } yield projected
//    Enumerator.enumerate(geojsons)
//  }


}
