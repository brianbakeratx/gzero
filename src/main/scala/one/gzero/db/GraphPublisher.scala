package one.gzero.db

import spray.json._
import java.sql.Timestamp
import akka.actor._
import one.gzero.api.{Edge => GEdge, Vertex => GVertex}
import gremlin.scala._
//import org.apache.tinkerpop.gremlin.driver.Cluster

import com.thinkaurelius.titan.core.TitanGraph

/* Inhererting this trait allows an app to simply create a titan graph object as graph = connect() */
trait LocalCassandraConnect {
  def connect(): TitanGraph = {
    import org.apache.commons.configuration.BaseConfiguration
    val conf = new BaseConfiguration()
    /* graph storage */
    conf.setProperty("storage.backend", "cassandra")
    conf.setProperty("storage.hostname", "127.0.0.1")
    /* indexing */
    /*
    conf.setProperty("index.search.backend", "elasticsearch")
    conf.setProperty("index.search.hostname" , "127.0.0.1")
    conf.setProperty("index.search.elasticsearch.client-only" ,  "true")
    conf.setProperty("index.search.elasticsearch.interface", "NODE")
    */
    import com.thinkaurelius.titan.core.TitanFactory
    TitanFactory.open(conf)
  }
}



class GraphPublisher() extends Actor with ActorLogging with LocalCassandraConnect {
  val graphJava = connect()
  val graph = graphJava.asScala
  val vertexIdCache = collection.mutable.Map[(String, String), Int]()

  val TimestampKey = Key[Timestamp]("timestamp")
  val EventSourceKey = Key[String]("event_source")
  /* the api allows for id, but we represent this as name inside of graph db, because the graph db has it's own id */
  val NameKey = Key[String]("name")
  val PrettyNameKey = Key[String]("prettyName")
  val RatingKey = Key[Double]("rating")


  def getOrCreateVertex(vertex : GVertex): Vertex = {
    val label = vertex.label
    val name = vertex.id
    val check = vertexIdCache.get(label, name)
    if (check.isDefined) {
      try {
        return graph.V(check.get).head()
      } catch {
       case e : Exception => {
          //log something
          vertexIdCache.remove(label,name)
          log.debug("stuff")
        }
      }
    }

    /* go ask the graph for the vertex */
    val answer = {
      val matches = graph.V.has(label, NameKey, name).toList()
      if (matches.length > 0) {
        println("matches:")
        println(matches)
        matches.head
      }
      else {
        /* create the vertex */
        graph +(label, NameKey -> name)
      }
    }
    val vId = answer.id().asInstanceOf[Int]
    vertexIdCache += ((label, name) -> vId)
    return answer
  }

  override def receive = {
    /* TODO - consider adding in some implicit conversions for converting between GVertex and Vertex */
    case vertex: GVertex => {
      val v = getOrCreateVertex(vertex)

      if (vertex.timestamp.isDefined)
      {
        val timestamp = Timestamp.valueOf(vertex.timestamp.get)
        v.setProperty(TimestampKey,timestamp)
      }

      if (vertex.properties.isDefined) {
        //TODO - update the properties of the vertex
      }
      graph.tx().commit()
      s"""$vertex"""
    }
    case edge: GEdge => {
      /* TODO - consider adding in some implicit conversions for converting between GEdge and Edge */
      val head = getOrCreateVertex(edge.head)
      val tail = getOrCreateVertex(edge.tail)

      val e = head.addEdge(edge.label, tail)

      if (edge.timestamp.isDefined)
      {
        val timestamp = Timestamp.valueOf(edge.timestamp.get)
        e.setProperty(TimestampKey,timestamp)
      }
      if ( edge.event_source.isDefined ) {
        e.setProperty(EventSourceKey, edge.event_source.get)
      }

      if (edge.properties.isDefined) {
        //TODO - update the properties of the edge
      }
      graph.tx().commit()
      s"""$edge"""
    }
  }
}
