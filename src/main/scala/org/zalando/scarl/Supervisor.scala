//
//  Copyright 2016 Zalando SE
//
//  Licensed under the Apache License, Version 2.0 (the "License");
//  you may not use this file except in compliance with the License.
//  You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
//  Unless required by applicable law or agreed to in writing, software
//  distributed under the License is distributed on an "AS IS" BASIS,
//  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  See the License for the specific language governing permissions and
//  limitations under the License.
//
package org.zalando.scarl

import akka.actor._
import akka.actor.SupervisorStrategy._
import akka.pattern.ask
import scala.annotation.tailrec
import scala.concurrent._
import scala.concurrent.duration._

/**
  *
  */
object Supervisor {
  implicit val t: akka.util.Timeout = 60 seconds

  /** resolve path to actor reference
    */
  def resolve(path: String)(implicit sys: ActorSystem): Future[ActorRef] = {
    sys.actorSelection("/user/" + path).resolveOne()
  }

  /** spawn children
    */
  def spawn(sup: ActorRef, spec: Instance): Future[ActorRef] = {
    sup.ask(Spawn(spec)).mapTo[ActorRef]
  }


  /** specification of children */
  sealed trait Instance {
    def id: String
    def props: Props
  }
  case class Worker(id: String, props: Props) extends Instance
  case class Supervisor(id: String, props: Props) extends Instance

  /** primitives */
  private[scarl] sealed trait Message
  private[scarl] case object Spawn extends Message
  private[scarl] case object Check extends Message
  private[scarl] case class Spawn(spec: Instance) extends Message

  /** failures */
  class RestartLimitExceeded extends RuntimeException
  class UnknownMessage extends RuntimeException

  /** state idenitity */
  sealed trait SID
  case object Config extends SID // Supervisor is busy to spawn child actors
  case object Active extends SID // Supervisor is active all actors are ready

  //
  implicit
  class SystemSupervisor(val sys: ActorSystem) extends scala.AnyRef {
    /** sequentially spawn root supervisor and it children
      */
    def supervisor(spec: Supervisor)(implicit sys: ActorSystem): ActorRef = {
      import akka.pattern.ask
      implicit val t: akka.util.Timeout = 60 seconds

      @tailrec
      def wait(sup: ActorRef): ActorRef = {
        Await.result(sup ? Check, Duration.Inf) match {
          case Config =>
            wait(sup)
          case Active =>
            sup
        }
      }
      wait(sys.actorOf(spec.props, spec.id))
    }
  }
}

//
// state definition
private[scarl] sealed trait State
private[scarl] case object Nothing extends State
private[scarl] case class Init(head: Option[ActorRef], list: Seq[Supervisor.Instance]) extends State

//
//
abstract
class Supervisor extends FSM[Supervisor.SID, State] with ActorLogging {
  /** specification of services to spawn*/
  def init: Seq[Supervisor.Instance]

  //
  val shutdown = false

  //
  startWith(Supervisor.Config, Init(None, init))

  //
  when(Supervisor.Config) {
    case Event(Supervisor.Spawn, Init(_, Nil)) =>
      context.parent ! ActorIdentity(None, Some(self))
      goto(Supervisor.Active) using Nothing

    case Event(Supervisor.Spawn, Init(_, x :: xs)) =>
      stay using Init(Some(spawn(x)), xs)

    case Event(ActorIdentity(_, pid), Init(head, list)) if pid == head =>
      self ! Supervisor.Spawn
      stay using Init(None, list)

    case Event(Supervisor.Check, _) =>
      sender() ! Supervisor.Config
      stay

    case _ =>
      throw new Supervisor.UnknownMessage
  }

  //
  when(Supervisor.Active) {
    case Event(Terminated(_), Nothing) if !shutdown =>
      throw new Supervisor.RestartLimitExceeded

    case Event(Terminated(_), Nothing)  =>
      context.system.terminate
      throw new Supervisor.RestartLimitExceeded

    case Event(ActorIdentity(None, _), Nothing) =>
      stay

    case Event(Supervisor.Check, _) =>
      sender() ! Supervisor.Active
      stay

    case Event(Supervisor.Spawn(spec), Nothing) =>
      sender() ! spawn(spec)
      stay
  }

  final
  override
  def preStart() = {
    self ! Supervisor.Spawn
  }

  private
  def spawn(spec: Supervisor.Instance): ActorRef =
    spec match {
      case Supervisor.Worker(id, props) =>
        log.info(s"spawn worker $id")
        val pid = context.watch(context.actorOf(props, id))
        pid ! Identify(id)
        pid

      case Supervisor.Supervisor(id, props) =>
        log.info(s"spawn supervisor $id")
        context.watch(context.actorOf(props, id))
    }

  protected
  def strategyOneForOne(maxN: Int, maxT: Duration) =
    OneForOneStrategy(maxNrOfRetries = maxN, withinTimeRange = maxT) {
      case _: Exception => Restart
    }

  protected
  def strategyAllForOne(maxN: Int, maxT: Duration) =
    AllForOneStrategy(maxNrOfRetries = maxN, withinTimeRange = maxT) {
      case _: Exception => Restart
    }
}

//
abstract
class RootSupervisor extends Supervisor {
  override val shutdown = true
}


