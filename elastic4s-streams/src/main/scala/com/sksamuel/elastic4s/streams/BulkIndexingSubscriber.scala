package com.sksamuel.elastic4s.streams

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import com.sksamuel.elastic4s.{BulkCompatibleDefinition, ElasticClient, ElasticDsl}
import org.elasticsearch.action.bulk.{BulkItemResponse, BulkResponse}
import org.reactivestreams.{Subscriber, Subscription}

import scala.collection.mutable.ArrayBuffer
import scala.util.{Failure, Success}

/**
 * An implementation of the reactive API Subscriber.
 * This subscriber will bulk index received elements. The bulk nature means that the elasticsearch
 * index operations are performed as a bulk call, the size of which are controlled by the batchSize param.
 *
 * The received elements must be converted into an elastic4s bulk compatible definition, such as index or delete.
 * This is done by the RequestBuilder.
 *
 * @param client used to connect to the cluster
 * @param builder used to turn elements of T into IndexDefinitions so they can be used in the bulk indexer
 * @param listener a listener which is notified on each acknowledge batch item
 * @param batchSize the number of elements to group together per batch aside from the last batch
 * @param concurrentRequests the number of concurrent batch operations
 * @param completionFn a function which is invoked when all sent requests have been acknowledged and the publisher has completed
 * @param errorFn a function which is invoked when there is an error
 *
 * @tparam T the type of element provided by the publisher this subscriber will subscribe with
 */
class BulkIndexingSubscriber[T](client: ElasticClient,
                                builder: RequestBuilder[T],
                                listener: ResponseListener,
                                batchSize: Int, concurrentRequests: Int,
                                completionFn: () => Unit,
                                errorFn: Throwable => Unit)
                               (implicit system: ActorSystem) extends Subscriber[T] {

  private var actor: ActorRef = _

  override def onSubscribe(s: Subscription): Unit = {
    if (s == null) throw new NullPointerException()
    if (actor == null) {
      actor = system.actorOf(
        Props(new BulkActor(client, builder, s, batchSize, concurrentRequests, listener, completionFn, errorFn))
      )
    } else {
      // rule 2.5, must cancel subscription as onSubscribe has been invoked twice
      // https://github.com/reactive-streams/reactive-streams-jvm#2.5
      s.cancel()
    }
  }

  override def onNext(t: T): Unit = {
    if (t == null) throw new NullPointerException()
    actor ! t
  }

  override def onError(t: Throwable): Unit = {
    if (t == null) throw new NullPointerException()
    actor ! t
  }

  override def onComplete(): Unit = {
    actor ! BulkActor.Completed
  }
}

object BulkActor {
  case object Completed
}

class BulkActor[T](client: ElasticClient,
                   builder: RequestBuilder[T],
                   subscription: Subscription,
                   batchSize: Int,
                   concurrentRequests: Int,
                   listener: ResponseListener,
                   completionFn: () => Unit,
                   errorFn: Throwable => Unit) extends Actor {

  import ElasticDsl._
  import context.dispatcher

  private val buffer = new ArrayBuffer[T]()
  buffer.sizeHint(batchSize)

  private var completed = false

  // total number of elements sent and acknowledge at the es cluster level
  private var pending: Long = 0l

  override def preStart(): Unit = {
    subscription.request(batchSize * concurrentRequests)
  }

  def receive = {
    case t: Throwable =>
      handleError(t)

    case BulkActor.Completed =>
      if (buffer.nonEmpty)
        index()
      completed = true
      shutdownIfAllAcked()

    case r: BulkResponse =>
      pending = pending - r.items.length
      r.items.foreach(listener.onAck)
      // need to check if we're completed, because if we are then this might be the last pending ack
      // and if it is, we can shutdown. Otherwise w can set another batch going.
      if (completed) shutdownIfAllAcked()
      else subscription.request(batchSize)

    case t: T =>
      buffer.append(t)
      if (buffer.size == batchSize)
        index()
  }

  private def shutdownIfAllAcked(): Unit = {
    if (pending == 0) {
      completionFn()
      context.stop(self)
    }
  }

  private def handleError(t: Throwable): Unit = {
    // if an error we will cancel the subscription as we cannot for sure handle further elements
    // and the error may be from outside the subscriber
    subscription.cancel()
    errorFn(t)
    buffer.clear()
    context.stop(self)
  }

  private def index(): Unit = {
    pending = pending + buffer.size
    client.execute( bulk(buffer.map(builder.request))).onComplete {
      case Failure(e) => self ! e
      case Success(resp) => self ! resp
    }
    buffer.clear
  }
}

/**
 * An implementation of this typeclass must provide a bulk compatible request for the given instance of T.
 * @tparam T the type of elements this provider supports
 */
trait RequestBuilder[T] {
  def request(t: T): BulkCompatibleDefinition
}

/**
 * Notified on each acknowledgement
 */
trait ResponseListener {
  def onAck(resp: BulkItemResponse): Unit
}

object ResponseListener {
  def noop = new ResponseListener {
    override def onAck(resp: BulkItemResponse): Unit = ()
  }
}