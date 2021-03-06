/*
 * Copyright (c) 2018 https://www.reactivedesignpatterns.com/
 *
 * Copyright (c) 2018 https://rdp.reactiveplatform.xyz/
 *
 */

package chapter11

import java.util.concurrent.TimeoutException

import akka.actor.{ Actor, ActorRef, Props }
import akka.util.Timeout
import com.reactivedesignpatterns.Defaults._

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }

class TranslationService {

  import ExecutionContext.Implicits.global

  def translate(input: String): Future[String] = Future {
    Thread.sleep(100)
    "How are you?"
  }

  def translate(input: String, ec: ExecutionContext): Future[String] =
    Future {
      Thread.sleep(100)
      "How are you?"
    }(ec)
}

/**
 * Another implementation that is based on Actors; this is used in the example
 * which shows how to assert the absence of indirectly invoked messages by way
 * of a protocol adapter test for this translation service.
 */
object TranslationService {

  final case class Translate(query: String, replyTo: ActorRef)

  private class Translator extends Actor {
    def receive: Receive = {
      case Translate(query, replyTo) =>
        query match {
          case "Hur mår du?" =>
            replyTo ! "How are you?"
          case _ =>
            replyTo ! s"error:cannot translate '$query'"
        }
    }
  }

  def props: Props = Props(new Translator)

  /**
   * Simplistic version 1 of the protocol: the reply will just be a String.
   */
  // #snip_11-17
  final case class TranslateV1(query: String, replyTo: ActorRef)

  // #snip_11-17

  /**
   * Implementation of the TranslateV1 protocol.
   */
  private class TranslatorV1 extends Actor {
    def receive: Receive = {
      case TranslateV1(query, replyTo) =>
        if (query == "sv:en:Hur mår du?") {
          replyTo ! "How are you?"
        } else {
          replyTo ! s"error:cannot translate '$query'"
        }
    }
  }

  def propsV1: Props = Props(new TranslatorV1)

  /**
   * More advanced version 2 of the protocol with proper reply types.
   * Languages are communicated as Strings for brevity, in a real project
   * these would be modeled as a proper Language type (statically known
   * enumeration or based on runtime registration of values).
   */
  // #snip_11-18
  final case class TranslateV2(phrase: String, inputLanguage: String, outputLanguage: String, replyTo: ActorRef)

  sealed trait TranslationResponseV2

  final case class TranslationV2(
      inputPhrase: String,
      outputPhrase: String,
      inputLanguage: String,
      outputLanguage: String)

  final case class TranslationErrorV2(
      inputPhrase: String,
      inputLanguage: String,
      outputLanguage: String,
      errorMessage: String)

  // #snip_11-18

  /**
   * Implementation of the TranslateV2 protocol based on TranslatorV1.
   */
  private class TranslatorV2(v1: ActorRef) extends Actor {
    private implicit val timeout: Timeout = Timeout(5.seconds)

    import context.dispatcher

    def receive: Receive = {
      case TranslateV2(phrase, in, out, replyTo) =>
        (v1 ? (TranslateV1(s"$in:$out:$phrase", _)))
          .collect {
            case str: String =>
              if (str.startsWith("error:")) {
                TranslationErrorV2(phrase, in, out, str.substring(6))
              } else {
                TranslationV2(phrase, str, in, out)
              }
          }
          .recover {
            case _: TimeoutException =>
              TranslationErrorV2(phrase, in, out, "timeout while talking to V1 back-end")
          }
          .pipeTo(replyTo)
    }
  }

  def propsV2(v1: ActorRef): Props = Props(new TranslatorV2(v1))
}
