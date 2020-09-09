/*
 * Copyright (c) 2018 https://www.reactivedesignpatterns.com/ 
 *
 * Copyright (c) 2018 https://rdp.reactiveplatform.xyz/
 *
 */

package chapter17

import java.net.URI
import java.util.concurrent.ThreadLocalRandom

import akka.actor._
import akka.persistence.journal._
import akka.persistence.query._
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.stream._
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._

//#snip_17-8
class ShoppingCartTagging(system: ExtendedActorSystem) extends WriteEventAdapter {
  def manifest(event: Any): String = "" // no additional manifest needed

  def toJournal(event: Any): Any =
    event match {
      case s: ShoppingCartMessage => Tagged(event, Set("shoppingCart"))
      case other                  => other
    }
}

//#snip_17-8

class ShoppingCartSimulator extends Actor with ActorLogging {
  def rnd: ThreadLocalRandom = ThreadLocalRandom.current

  private val items: Array[ItemRef] = Array("apple", "banana", "plum", "pear", "peach").map(f => ItemRef(new URI(f)))

  def pickItem(): ItemRef = items(rnd.nextInt(items.length))

  private val customers: Array[CustomerRef] =
    Array("alice", "bob", "charlie", "mallory").map(c => CustomerRef(new URI(c)))

  def pickCustomer(): CustomerRef = customers(rnd.nextInt(customers.length))

  private val id: Iterator[Int] = Iterator.from(0)

  def command(cmd: Command) = ManagerCommand(cmd, id.next, self)

  def driveCart(num: Int): Unit = {
    val cartRef = ShoppingCartRef(new URI(f"cart$num%08X"))
    val manager = context.actorOf(Props(new PersistentObjectManager), cartRef.id.toString)
    manager ! command(SetOwner(cartRef, pickCustomer()))
    while (rnd.nextDouble() < 0.95) {
      val cmd =
        if (rnd.nextBoolean()) AddItem(cartRef, pickItem(), rnd.nextInt(14) + 1)
        else RemoveItem(cartRef, pickItem(), rnd.nextInt(10) + 1)
      manager ! command(cmd)
    }
    manager ! ManagerQuery(GetItems(cartRef), num, self)
  }

  final case class Cont(id: Int)

  self ! Cont(0)

  def receive: Receive = {
    case Cont(n)             => driveCart(n)
    case ManagerEvent(id, _) => if (id % 10000 == 0) log.info("done {} commands", id)
    case ManagerResult(num, GetItemsResult(cart, items)) =>
      context.stop(context.child(cart.id.toString).get)
      self ! Cont(num.toInt + 1)
  }
}

final case class GetTopProducts(id: Long, replyTo: ActorRef)

final case class TopProducts(id: Long, products: Map[ItemRef, Int])

//#snip_17-9
object TopProductListener {

  private class IntHolder(var value: Int)

}

class TopProductListener extends Actor with ActorLogging {

  import TopProductListener._

  private implicit val materializer: ActorMaterializer = ActorMaterializer()

  private val readJournal: LeveldbReadJournal =
    PersistenceQuery(context.system).readJournalFor[LeveldbReadJournal](LeveldbReadJournal.Identifier)

  readJournal
    .eventsByTag(tag = "shoppingCart", offset = Sequence(0L))
    .collect { case EventEnvelope(_, _, _, add: ItemAdded) => add }
    .groupedWithin(100000, 1.second)
    .addAttributes(Attributes.asyncBoundary)
    .runForeach { seq: Seq[ItemAdded] =>
      val histogram = seq.foldLeft(Map.empty[ItemRef, IntHolder]) { (map, event) =>
        map.get(event.item) match {
          case Some(holder) =>
            holder.value += event.count
            map
          case None =>
            map.updated(event.item, new IntHolder(event.count))
        }
      }
      self ! TopProducts(0, histogram.map(p => (p._1, p._2.value)))
    }

  private var topProducts = Map.empty[ItemRef, Int]

  def receive: Receive = {
    case GetTopProducts(id, replyTo) =>
      replyTo ! TopProducts(id, topProducts)
    case TopProducts(_, products) =>
      topProducts = products
      log.info("new {}", products)
  }
}

//#snip_17-9

object EventStreamExample extends App {
  val config = ConfigFactory.parseString("""
akka.loglevel = INFO
akka.actor.debug.unhandled = on
akka.actor.warn-about-java-serializer-usage = off
akka.persistence.snapshot-store.plugin = "akka.persistence.no-snapshot-store"
akka.persistence.journal {
  plugin = "akka.persistence.journal.leveldb"
  leveldb {
    native = off
    event-adapters {
      tagging = "com.reactivedesignpatterns.chapter17.ShoppingCartTagging"
    }
    event-adapter-bindings {
      "com.reactivedesignpatterns.chapter17.ShoppingCartMessage" = tagging
    }
  }
}
""")
  val sys = ActorSystem("EventStream", config)
  sys.actorOf(Props(new ShoppingCartSimulator), "simulator")
  sys.actorOf(Props(new TopProductListener), "listener")
}
