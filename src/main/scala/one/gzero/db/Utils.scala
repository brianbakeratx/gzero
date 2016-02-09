package one.gzero.db

import java.net.URLEncoder

import com.thinkaurelius.titan.core.TitanGraph
import java.sql.Timestamp
import one.gzero.api.{Edge => GEdge, Vertex => GVertex}
import gremlin.scala._
import spray.can.Http.ConnectionAttemptFailedException
import spray.json.{JsObject}
import scala.concurrent.{TimeoutException, Await}
import scala.concurrent.duration._
import akka.actor.ActorSystem
import spray.json._
import DefaultJsonProtocol._
import spray.httpx.SprayJsonSupport
import spray.client.pipelining._
import SprayJsonSupport._
import org.apache.tinkerpop.gremlin.structure.io.graphson.GraphSONReader

/* Inhererting this trait allows an app to simply create a titan graph object as graph = connect() */
trait LocalCassandraConnect {
  def connect(): TitanGraph = {
    import org.apache.commons.configuration.BaseConfiguration
    val conf = new BaseConfiguration()

    // graph storage
    conf.setProperty("storage.backend", "cassandra")
    conf.setProperty("storage.hostname", "127.0.0.1")

    // indexing
    conf.setProperty("index.search.backend", "elasticsearch")
    conf.setProperty("index.search.hostname" , "127.0.0.1")
    conf.setProperty("index.search.elasticsearch.client-only" ,  "true")
//    conf.setProperty("index.search.elasticsearch.interface", "NODE")

    import com.thinkaurelius.titan.core.TitanFactory
    TitanFactory.open(conf)
  }
}

trait VertexCache {
    val graph : TitanGraph
    val TimestampKey = Key[Timestamp]("timestamp")
    val EventSourceKey = Key[String]("event_source")
    /* the api allows for id, but we represent this as name inside of graph db, because the graph db has it's own id */
    val NameKey = Key[String]("name")
    val PrettyNameKey = Key[String]("prettyName")
    val RatingKey = Key[Double]("rating")
    val vertexIdCache = collection.mutable.Map[(String, String), Long]()

    def getOrCreateVertex(vertex: GVertex): Vertex = {
        val label = vertex.label
        val name = vertex.getProperty("name")
        val check = vertexIdCache.get(label, name)
        if (check.isDefined) {
          try {
            return graph.V(check.get).head()
          } catch {
            case e: Exception => {
              //log something
              vertexIdCache.remove(label, name)
            }
          }
        }

        /* go ask the graph for the vertex */
        val answer = {
          val matches = graph.V.has(label, NameKey, name).toList()
          if (matches.length > 0) {
            matches.head
          }
          else {
            /* create the vertex */
            graph +(label, NameKey -> name)
          }
        }
        val vId = answer.id().asInstanceOf[Long]
        vertexIdCache += ((label, name) -> vId)
        graph.tx().commit()
        return answer
    }
}

case class GremlinResult(requestId: String, result: JsObject, status: JsObject)

//GraphSON is used for returning objects. The format is very similar to the syntax for adding edges/vertices.
//GZero API provides a simpler method for indicating "head" and "tail" vertices on edges. It is still possible to add
//vertices and edges using the standard gremlin api. g.addVertex...
//the difference is that in GraphSON the edges indicate their head and tail through the
//outV and inV vertices with internal graph ids.
//{"inVLabel":"vehicle","outV":8256,"label":"drove","outVLabel":"person","id":"2rs-6dc-4r9-6hs","type":"edge","inV":8416}
case class GraphSONEdge(outV:Int, inV:Int, label:String, properties: Option[JsObject] )
case class GraphSONVertex(label:String, id:Option[Int], properties: Option[JsObject])
case class NameProperty (name:String)

object GremlinResultJsonProtocol extends DefaultJsonProtocol {
  implicit val gresult = jsonFormat3(GremlinResult)
  implicit val gedge = jsonFormat4(GraphSONEdge)
  implicit val gvertex = jsonFormat3(GraphSONVertex)
}

trait LocalGremlinQuery {
  def query(query : String): String = {
    val jsonQuery = query.toJson

    implicit val system = ActorSystem("gzero-api")
    import system.dispatcher

    import GremlinResultJsonProtocol._

    val pipeline = sendReceive ~> unmarshal[GremlinResult]

    try {
      val responseFuture = pipeline {
        Get("http://localhost:8182?gremlin=" + URLEncoder.encode(query, "UTF-8"))
      }

      Await.result(responseFuture, 24 hours)
      val graphSon =responseFuture.value.get.get.result.fields("data")
      graphSon.toString
    }
    catch {
      case ca: ConnectionAttemptFailedException => {
        ca.printStackTrace()
        s"""{"error": true, "message": "Connection to gremlin server failed"}"""
      }
      case te: TimeoutException => {
        te.printStackTrace()
        s"""{"error": true, "message": "Timeout executing query", "gremlin": $jsonQuery}"""
      }
      case e: Exception => {
        e.printStackTrace()
        s"""{"error": true, "message": "An unknown error occurred, check the query syntax", "gremlin": $jsonQuery}"""
      }
    }
  }
}
