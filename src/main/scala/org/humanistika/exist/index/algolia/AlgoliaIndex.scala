package org.humanistika.exist.index.algolia

import java.nio.file.Path

import org.apache.logging.log4j.{LogManager, Logger}
import org.exist.indexing.{AbstractIndex, IndexWorker}
import org.exist.storage.{BrokerPool, DBBroker}
import org.w3c.dom.Element
import AlgoliaIndex._
import akka.actor.{ActorRef, ActorSystem, Props}
import org.humanistika.exist.index.algolia.backend.IncrementalIndexingManagerActor
import org.humanistika.exist.index.algolia.backend.IncrementalIndexingManagerActor.DropIndexes

import scala.collection.JavaConverters._

object AlgoliaIndex {
  private val LOG: Logger = LogManager.getLogger(classOf[AlgoliaIndex])
  val ID: String = AlgoliaIndex.getClass.getName
  val SYSTEM_NAME = "AlgoliaIndex"
  case class Authentication(applicationId: String, adminApiKey: String)
}

class AlgoliaIndex extends AbstractIndex {
  private var system: Option[ActorSystem] = None
  private var incrementalIndexingManagerActor: Option[ActorRef] = None
  private var apiAuthentication: Option[Authentication] = None

  override def configure(pool: BrokerPool, dataDir: Path, config: Element) {
    // get the authentication credentials from the config
    val applicationId = Option(config.getAttribute("application-id"))
    val adminApiKey = Option(config.getAttribute("admin-api-key"))
    if(applicationId.isEmpty) {
      LOG.error("You must specify an Application ID for use with Algolia")
    }
    if(adminApiKey.isEmpty) {
      LOG.error("You must specify an Admin API Key for use with Algolia")
    }

    this.apiAuthentication = applicationId.flatMap(id => adminApiKey.map(key => Authentication(id, key)))

    super.configure(pool, dataDir, config)
  }

  override def open() {
    this.system = Some(ActorSystem(SYSTEM_NAME))
    this.incrementalIndexingManagerActor = system.map(_.actorOf(Props(classOf[IncrementalIndexingManagerActor], getDataDir), IncrementalIndexingManagerActor.ACTOR_NAME))
    this.incrementalIndexingManagerActor.foreach(actor => apiAuthentication.foreach(auth => actor ! auth))

    // recommended by Algolia
    java.security.Security.setProperty("networkaddress.cache.ttl", "60")
  }

  override def close() {
    system.foreach(_.shutdown())
  }

  override def getWorker(broker: DBBroker): IndexWorker = {
    system match {
      case Some(sys) if !sys.isTerminated =>
        new AlgoliaIndexWorker(this, broker, sys)
      case _ =>
        null
    }
  }

  override def remove(): Unit = {
    incrementalIndexingManagerActor.foreach(_ ! DropIndexes)
  }

  override def checkIndex(broker: DBBroker) = false

  override def sync() {
    //TODO(AR) implement?
  }
}
