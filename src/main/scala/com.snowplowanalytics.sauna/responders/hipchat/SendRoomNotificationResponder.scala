/*
 * Copyright (c) 2017 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.snowplowanalytics.sauna
package responders
package hipchat

// scala
import scala.concurrent.ExecutionContext.Implicits.global
import scala.io.Source
import scala.util.{Failure, Success}

// play
import play.api.libs.json._

// akka
import akka.actor.{ActorRef, Props}

// sauna
import Responder._
import SendRoomNotificationResponder._
import apis.Hipchat
import apis.Hipchat._
import loggers.Logger.Notification
import observers.Observer.ObserverBatchEvent
import utils.Command

class SendRoomNotificationResponder(hipchat: Hipchat, val logger: ActorRef) extends Responder[RoomNotificationReceived] {
  override def extractEvent(observerEvent: ObserverBatchEvent): Option[RoomNotificationReceived] = {
    observerEvent.streamContent match {
      case Some(is) =>
        val commandJson = Json.parse(Source.fromInputStream(is).mkString)
        Command.extractCommand[RoomNotification](commandJson) match {
          case Right((envelope, data)) =>
            Command.validateEnvelope(envelope) match {
              case None =>
                Some(RoomNotificationReceived(data, observerEvent))
              case Some(error) =>
                logger ! Notification(error)
                None
            }
          case Left(error) =>
            logger ! Notification(error)
            None
        }
      case None =>
        logger ! Notification("No stream present, cannot parse command")
        None
    }
  }

  /**
   * Send a valid room notification using the API wrapper.
   *
   * @param event The event containing a room notification.
   */
  override def process(event: RoomNotificationReceived): Unit =
    hipchat.sendRoomNotification(event.data).onComplete {
      case Success(message) => context.parent ! RoomNotificationSent(event, s"Successfully sent HipChat notification: $message")
      case Failure(error) => logger ! Notification(s"Error while sending HipChat notification: $error")
    }
}

object SendRoomNotificationResponder {
  case class RoomNotificationReceived(
    data: RoomNotification,
    source: ObserverBatchEvent
  ) extends ResponderEvent[ObserverBatchEvent]

  /**
   * A responder result denoting that a HipChat room notification was successfully sent
   * by the responder.
   *
   * @param source  The responder event that triggered this.
   * @param message A success message.
   */
  case class RoomNotificationSent(
    source: RoomNotificationReceived,
    message: String) extends ResponderResult

  /**
   * Constructs a [[Props]] for a [[SendRoomNotificationResponder]] actor.
   *
   * @param hipchat The HipChat API wrapper.
   * @param logger  A logger actor.
   * @return [[Props]] for the new actor.
   */
  def props(hipchat: Hipchat, logger: ActorRef): Props =
    Props(new SendRoomNotificationResponder(hipchat, logger))
}