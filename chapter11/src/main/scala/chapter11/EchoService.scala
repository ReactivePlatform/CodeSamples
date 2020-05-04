/*
 * Copyright (c) 2018 https://www.reactivedesignpatterns.com/
 *
 * Copyright (c) 2018 https://rdp.reactiveplatform.xyz/
 *
 */

package chapter11

import akka.actor.{ Actor, ActorRef }

object EchoService {

  final case class Request(tag: String, client: ActorRef)

  final case class Response(tag: String)

}

class EchoService extends Actor {

  import EchoService._

  def receive: Receive = {
    case Request(tag, ref) => ref ! Response(tag)
  }
}
