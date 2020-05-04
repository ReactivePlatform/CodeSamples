/*
 * Copyright (c) 2018 https://www.reactivedesignpatterns.com/
 *
 * Copyright (c) 2018 https://rdp.reactiveplatform.xyz/
 *
 */

package chapter11

import akka.actor.{ actorRef2Scala, Actor, ActorRef, ActorSystem, Props }
import akka.pattern.pipe
import akka.util.Timeout
import com.reactivedesignpatterns.Defaults.AskableActorRef

import scala.collection.immutable
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ ExecutionContext, Future }
import scala.reflect.classTag

object LatencyTestSupport {

  final case class SingleResult[T](future: Future[T], expected: T)

  final case class SummaryResult(timings: immutable.Seq[FiniteDuration], failures: immutable.Seq[Throwable]) {
    lazy val sorted: immutable.Seq[FiniteDuration] = timings.sorted

    lazy val failureCount: Int = failures.size
    lazy val failureRatio: Double = failureCount.toDouble / (timings.size + failureCount)

    /**
     * Return the quantile of the timings that is described by the parameter q
     * in the range from zero to one. For example the 99th percentile is
     * obtained for `q = 0.99`.
     */
    def quantile(q: Double): FiniteDuration = sorted.takeRight(Math.max(1, ((1 - q) * timings.size).toInt)).head

    override def toString: String =
      f"""|SummaryResult($failureCount failures [${failureRatio * 100}% 6.2f%%] in ${timings.size} samples)
          |        minimum         = ${sorted.head}
          |        50th percentile = ${quantile(0.5)}
          |        95th percentile = ${quantile(0.95)}
          |        99th percentile = ${quantile(0.99)}
          |        maximum         = ${sorted.last}""".stripMargin
  }

  private final case class RunMeasurement(
      count: Int,
      maxParallelism: Int,
      ec: ExecutionContext,
      f: Int => SingleResult[_],
      replyTo: ActorRef)

  private class Supervisor extends Actor {
    def receive: Receive = {
      case r: RunMeasurement => context.actorOf(runnerProps(r))
    }
  }

  private def runnerProps(r: RunMeasurement): Props =
    Props(new TestRunner(r.count, r.maxParallelism, r.f, r.replyTo)(r.ec))

  private sealed trait TestResult

  private final case class TestSuccess(duration: FiniteDuration) extends TestResult

  private final case class TestFailure(ex: Throwable) extends TestResult

  private object TestFailure extends PartialFunction[Throwable, TestFailure] {
    override def isDefinedAt(ex: Throwable) = true
  }

  private class TestRunner(count: Int, maxParallelism: Int, f: Int => SingleResult[_], replyTo: ActorRef)(
      implicit ec: ExecutionContext)
      extends Actor {

    private var sent = 0
    private var received = 0
    private var results = Vector.empty[FiniteDuration]
    private var failures = Vector.empty[Throwable]

    override def preStart(): Unit = {
      val tryNow = Math.min(count, maxParallelism)
      (0 until tryNow).foreach(send)
    }

    def send(i: Int): Unit = {
      val start = Timestamp.now
      val r = f(i)
      r.future
        .map { v =>
          val stop = Timestamp.now
          assert(v == r.expected, s"$v did not equal ${r.expected}")
          (stop - start).toFiniteDuration
        }
        .map(TestSuccess)
        .recover(TestFailure)
        .pipeTo(self)
      sent += 1
    }

    def receive: Receive = {
      case TestSuccess(timing) =>
        results :+= timing
        nextOrFinish()
      case TestFailure(ex) =>
        failures :+= ex
        nextOrFinish()
    }

    def nextOrFinish(): Unit = {
      received += 1
      if (received == count) {
        replyTo ! SummaryResult(results, failures)
        context.stop(self)
      } else if (sent < count) {
        send(sent)
      }
    }
  }

}

class LatencyTestSupport(system: ActorSystem) {

  import LatencyTestSupport._

  private val supervisor = system.actorOf(Props[Supervisor], "LatencyTestSupportSupervisor")

  def measure(count: Int, maxParallelism: Int)(
      f: Int => SingleResult[_])(implicit timeout: Timeout, ec: ExecutionContext): Future[SummaryResult] = {
    (supervisor ? (RunMeasurement(count, maxParallelism, ec, f, _))).mapTo(classTag[SummaryResult])
  }

}
