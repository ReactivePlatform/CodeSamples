/*
 * Copyright (c) 2018 https://www.reactivedesignpatterns.com/
 *
 * Copyright (c) 2018 https://rdp.reactiveplatform.xyz/
 *
 */

package chapter15.pattern.ask

import java.util.UUID

import akka.typed.ActorRef
import chapter15.StatusCode

sealed trait MyCommands

private final case class MyEmailResult(correlationID: UUID, status: StatusCode, explanation: Option[String])
    extends MyCommands

// #snip
final case class StartVerificationProcess(userEmail: String, replyTo: ActorRef[VerificationProcessResponse])
    extends MyCommands

sealed trait VerificationProcessResponse

final case class VerificationProcessStarted(userEmail: String) extends VerificationProcessResponse

final case class VerificationProcessFailed(userEmail: String) extends VerificationProcessResponse

// #snip
