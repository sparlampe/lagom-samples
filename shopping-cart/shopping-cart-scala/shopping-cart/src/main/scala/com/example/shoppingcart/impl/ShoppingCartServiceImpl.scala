package com.example.shoppingcart.impl

import akka.NotUsed
import com.example.shoppingcart.api.ShoppingCartView
import com.example.shoppingcart.api.ShoppingCartItem
import com.example.shoppingcart.api.Quantity
import com.example.shoppingcart.api.ShoppingCartReport
import com.example.shoppingcart.api.ShoppingCartService

import com.example.shoppingcart.impl.ShoppingCart._

import com.lightbend.lagom.scaladsl.api.ServiceCall
import com.lightbend.lagom.scaladsl.api.broker.Topic
import com.lightbend.lagom.scaladsl.api.transport.BadRequest
import com.lightbend.lagom.scaladsl.api.transport.NotFound
import com.lightbend.lagom.scaladsl.broker.TopicProducer
import com.lightbend.lagom.scaladsl.persistence.EventStreamElement
import com.lightbend.lagom.scaladsl.persistence.PersistentEntityRegistry

import scala.concurrent.ExecutionContext
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import scala.concurrent.duration._
import akka.util.Timeout
import akka.cluster.sharding.typed.scaladsl.EntityRef
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import com.example.inventory.TracingUtils.{getCurrentSpanContext}
import io.opentracing.{Scope, Span, SpanContext}
import io.opentracing.propagation.{Format, TextMapAdapter}
import io.opentracing.util.GlobalTracer
import scala.jdk.CollectionConverters._
import org.slf4j.LoggerFactory

/**
 * Implementation of the `ShoppingCartService`.
 */
class ShoppingCartServiceImpl(
    clusterSharding: ClusterSharding,
    persistentEntityRegistry: PersistentEntityRegistry,
    reportRepository: ShoppingCartReportRepository
)(implicit ec: ExecutionContext)
    extends ShoppingCartService {

  val log = LoggerFactory.getLogger(getClass)
  /**
   * Looks up the shopping cart entity for the given ID.
   */
  private def entityRef(id: String): EntityRef[Command] =
    clusterSharding.entityRefFor(ShoppingCart.typeKey, id)

  implicit val timeout = Timeout(5.seconds)

  override def get(id: String): ServiceCall[NotUsed, ShoppingCartView] = ServiceCall { _ =>
    entityRef(id)
      .ask(reply => Get(reply))
      .map(cartSummary => convertShoppingCart(id, cartSummary))
  }

  override def addItem(id: String): ServiceCall[ShoppingCartItem, ShoppingCartView] = ServiceCall { update =>
    entityRef(id)
      .ask(reply => AddItem(update.itemId, update.quantity, reply))
      .map { confirmation =>
        confirmationToResult(id, confirmation)
      }
  }

  override def removeItem(id: String, itemId: String): ServiceCall[NotUsed, ShoppingCartView] = ServiceCall { update =>
    entityRef(id)
      .ask(reply => RemoveItem(itemId, reply))
      .map { confirmation =>
        confirmationToResult(id, confirmation)
      }
  }

  override def adjustItemQuantity(id: String, itemId: String): ServiceCall[Quantity, ShoppingCartView] = ServiceCall {
    update =>
      entityRef(id)
        .ask(reply => AdjustItemQuantity(itemId, update.quantity, reply))
        .map { confirmation =>
          confirmationToResult(id, confirmation)
        }
  }

  override def checkout(id: String): ServiceCall[NotUsed, ShoppingCartView] = ServiceCall { _ =>
    log.info(s"$id - checking out cart")
    val currentSpanContext =  getCurrentSpanContext
    entityRef(id)
      .ask(replyTo => Checkout(replyTo, currentSpanContext))
      .map { confirmation =>
        confirmationToResult(id, confirmation)
      }
  }

  private def confirmationToResult(id: String, confirmation: Confirmation): ShoppingCartView =
    confirmation match {
      case Accepted(cartSummary) => convertShoppingCart(id, cartSummary)
      case Rejected(reason)      => throw BadRequest(reason)
    }

  override def shoppingCartTopic: Topic[ShoppingCartView] = TopicProducer.taggedStreamWithOffset(Event.Tag) {
    (tag, fromOffset) =>
      persistentEntityRegistry
        .eventStream(tag, fromOffset)
        .filter(_.event.isInstanceOf[CartCheckedOut])
        .map(_.asInstanceOf[EventStreamElement[CartCheckedOut]])
        .via(new Extract)
        .mapAsync(4) {
          case EventStreamElement(id, _, offset) =>
            entityRef(id)
              .ask(reply => Get(reply))
              .map(cart =>  convertShoppingCart(id, cart)-> offset)
        }
  }

  private def convertShoppingCart(id: String, cartSummary: Summary) = {
    ShoppingCartView(
      id,
      cartSummary.items.map((ShoppingCartItem.apply _).tupled).toSeq,
      cartSummary.checkedOut
    )
  }

  override def getReport(cartId: String): ServiceCall[NotUsed, ShoppingCartReport] = ServiceCall { _ =>
    reportRepository.findById(cartId).map {
      case Some(cart) => cart
      case None       => throw NotFound(s"Couldn't find a shopping cart report for $cartId")
    }
  }
}


class Extract extends GraphStage[FlowShape[EventStreamElement[CartCheckedOut], EventStreamElement[CartCheckedOut]]] {
  val in = Inlet[EventStreamElement[CartCheckedOut]]("extract.in")
  val out = Outlet[EventStreamElement[CartCheckedOut]]("extract.out")
  override val shape = FlowShape(in, out)

  override def initialAttributes: Attributes = Attributes.name("extract")

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with InHandler with OutHandler {
      override def onPush(): Unit = {
        val tracer = GlobalTracer.get()
        val message = grab(in)
        
        val context: SpanContext = tracer.extract(
          Format.Builtin.TEXT_MAP,
          new TextMapAdapter(message.event.spanContext.asJava))

        
        val producerSpan: Span = tracer
          .buildSpan("producer")
          .asChildOf(context)
          .start()

        val producerScope: Scope = tracer.activateSpan(producerSpan)
        val mdcScope = org.slf4j.MDC.putCloseable("Trace-ID", GlobalTracer.get().activeSpan().context().toTraceId)
        
        push(out, message)
        
        mdcScope.close()
        producerSpan.finish()
        producerScope.close()
      }

      override def onPull(): Unit = pull(in)

      setHandlers(in, out, this)
    }
}