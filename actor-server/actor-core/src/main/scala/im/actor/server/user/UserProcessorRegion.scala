package im.actor.server.user

import akka.actor.{ ActorRef, ActorSystem, Props }
import akka.cluster.sharding.{ ClusterSharding, ClusterShardingSettings, ShardRegion }
import akka.event.Logging
import im.actor.server.dialog.DialogEnvelope
import im.actor.server.model.{ Peer, PeerType }

import scala.util.{ Try, Success }

object UserProcessorRegion {
  private def extractEntityId(system: ActorSystem): ShardRegion.ExtractEntityId = {
    val log = Logging(system, getClass)

    {
      case c: UserCommand ⇒ (c.userId.toString, c)
      case q: UserQuery   ⇒ (q.userId.toString, q)
      case e @ DialogEnvelope(peer, command, query) ⇒ peer match {
        case Peer(PeerType.Private, userId) ⇒
          Try(e.getField(DialogEnvelope.descriptor.findFieldByNumber(command.number))) match {
            case Success(any) ⇒ (userId.toString, any)
            case _ ⇒
              val error = new RuntimeException(s"Payload not found for $e")
              log.error(error, error.getMessage)
              throw error
          }
        case Peer(peerType, _) ⇒ throw new RuntimeException(s"DialogCommand with peerType: $peerType passed in UserProcessor")
      }
      case e @ DialogRootEnvelope(userId, query, command) ⇒
        (
          userId.toString,
          if (query.isDefined) e.getField(DialogRootEnvelope.descriptor.findFieldByNumber(query.number))
          else if (command.isDefined) e.getField(DialogRootEnvelope.descriptor.findFieldByNumber(command.number))
          else throw new RuntimeException("No defined query nor command")
        )
    }
  }

  private def extractShardId(system: ActorSystem): ShardRegion.ExtractShardId = {
    case c: UserCommand ⇒ (c.userId % 100).toString // TODO: configurable
    case q: UserQuery   ⇒ (q.userId % 100).toString
    case DialogEnvelope(peer, _, _) ⇒ peer match {
      case Peer(PeerType.Private, userId) ⇒ (userId % 100).toString
      case Peer(peerType, _)              ⇒ throw new RuntimeException(s"DialogCommand with peerType: $peerType passed in UserProcessor")
    }
    case DialogRootEnvelope(userId, _, _) ⇒ (userId % 100).toString
  }

  val typeName = "UserProcessor"

  private def start(props: Props)(implicit system: ActorSystem): UserProcessorRegion =
    UserProcessorRegion(ClusterSharding(system).start(
      typeName = typeName,
      entityProps = props,
      settings = ClusterShardingSettings(system),
      extractEntityId = extractEntityId(system),
      extractShardId = extractShardId(system)
    ))

  def start()(implicit system: ActorSystem): UserProcessorRegion =
    start(UserProcessor.props)

  def startProxy()(implicit system: ActorSystem): UserProcessorRegion =
    UserProcessorRegion(ClusterSharding(system).startProxy(
      typeName = typeName,
      role = None,
      extractEntityId = extractEntityId(system),
      extractShardId = extractShardId(system)
    ))
}

final case class UserProcessorRegion(ref: ActorRef)