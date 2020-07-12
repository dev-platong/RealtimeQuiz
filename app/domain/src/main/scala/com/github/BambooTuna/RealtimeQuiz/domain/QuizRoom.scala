package com.github.BambooTuna.RealtimeQuiz.domain

import akka.{Done, NotUsed}
import akka.stream.{KillSwitches, Materializer, SharedKillSwitch}
import akka.stream.scaladsl.{Flow, Sink}
import com.github.BambooTuna.RealtimeQuiz.domain.AccountRole.{
  Admin,
  Player,
  Spectator
}
import com.github.BambooTuna.RealtimeQuiz.domain.CurrentStatus.{
  CloseAnswer,
  OpenAggregate,
  OpenAnswer,
  WaitingQuestion
}
import com.github.BambooTuna.RealtimeQuiz.domain.lib.StreamSupport
import com.github.BambooTuna.RealtimeQuiz.domain.ws._
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.Try

abstract class QuizRoom(val roomId: String, val roomName: String)(
    implicit materializer: Materializer) {
  implicit val executionContext: ExecutionContextExecutor =
    materializer.executionContext

  val logger: Logger = LoggerFactory.getLogger(getClass)

  var parent: Account
  var children: Set[Account] = Set.empty
  var currentStatus: CurrentStatus = WaitingQuestion
  var currentQuestion: Option[String] = None

  protected val killSwitch: SharedKillSwitch = KillSwitches.shared(roomId)
  protected val (actorRef, source) = StreamSupport
    .actorSourceWithKillSwitch[WebSocketMessageWithDestination](killSwitch,
                                                                setIgnoreSink =
                                                                  false)

  protected def isParent(id: String): Boolean = this.parent.id == id
  protected def isChild(id: String): Boolean = this.children.exists(_.id == id)
  protected def canAnswer(id: String): Boolean =
    this.children.exists(a => a.id == id && a.role == Player && !a.isAnswered)

  def join(accountId: String, isSpectator: Boolean): Try[Unit] = Try {
    require(children.size < 10, "満員です >= 10")
    if (!isParent(accountId) && !isChild(accountId)) {
      this.children = this.children + Account.apply(accountId,
                                                    if (isSpectator) Spectator
                                                    else Player)
    }
  }

  def leave(accountId: String): Try[Unit] = Try {
    changeAccountStatus(accountId, _.leave)
  }

  def closable(accountId: String): Boolean = {
    val r = isParent(accountId)
    if (r) killSwitch.shutdown()
    r
  }

  def getConnection(accountId: String)
    : Try[Flow[WebSocketMessage, WebSocketMessage, NotUsed]] = Try {
    changeAccountStatus(accountId, _.activate)
    noticeEveryone()
    Flow
      .fromSinkAndSource(sink(accountId),
                         source via destinationFilterFlow(accountId))
      .watchTermination()((_, f) => {
        f.onComplete(_ =>
          actorRef ! WebSocketMessage.connectionClosed(accountId))(
          materializer.executionContext); NotUsed
      })
  }

  protected def noticeEveryone(): Future[Unit] = {
    Future {
      Thread.sleep(500)
      noticePlayersState(actorRef ! _)
    }
  }

  protected def changeQuizRoomStatus(f: QuizRoom => Unit): Unit = {
    f(this)
  }
  protected def changeAccountStatus(accountId: String,
                                    f: Account => Account): Unit = {
    if (isParent(accountId)) this.parent = f(this.parent)
    children
      .find(_.id == accountId)
      .map(f)
      .foreach { account =>
        this.children = this.children - account + account
      }
  }

  protected def noticePlayersState(
      f: WebSocketMessageWithDestination => Unit): Unit = {
    val everyone = this.children + parent
    f(
      WebSocketMessageWithDestination(
        PlayerList(
          currentStatus = currentStatus,
          currentQuestion = currentQuestion,
          currentTimeLimit = None,
          players = everyone.toSeq
        ),
        User(this.parent.id)
      )
    )

    f(
      WebSocketMessageWithDestination(
        PlayerList(
          currentStatus = currentStatus,
          currentQuestion = currentQuestion,
          currentTimeLimit = None,
          players = everyone.map(_.hideAnswer).toSeq
        ),
        Users(this.children.map(_.id).toSeq)
      )
    )
  }

  protected def destinationFilterFlow(accountId: String)
    : Flow[WebSocketMessageWithDestination, WebSocketMessage, NotUsed] =
    Flow[WebSocketMessageWithDestination]
      .filter(_.destination.accessible(accountId))
      .map(_.data)

  protected def sink(accountId: String): Sink[WebSocketMessage, Future[Done]] =
    Sink.foreach[WebSocketMessage] {
      case v: ParseError =>
        logger.error(s"RoomId -> $roomId, $v")
      case v: PlayerList =>
        logger.debug(s"RoomId -> $roomId, $v")
      case v: ConnectionClosed =>
        logger.debug(s"RoomId -> $roomId, $v")
      case v: ChangeName =>
        changeAccountStatus(accountId, _.rename(v.accountName))
        noticeEveryone()
      case v: SetQuestion =>
        if (isParent(accountId) && v.question.nonEmpty && this.currentStatus == WaitingQuestion) {
          changeQuizRoomStatus(_ => {
            this.currentStatus = this.currentStatus.next
            this.currentQuestion = Some(v.question)
            //TODO タイマーをセットし一定時間後にforceSendAnswerを全員に送信
          })
          noticeEveryone()
        }
      case v: SetAnswer =>
        if (canAnswer(accountId) && this.currentStatus == CloseAnswer) {
          changeAccountStatus(accountId, _.setAnswer(v.answer))
          noticeEveryone()
        }
      case v: SetAlterStars =>
        if (isParent(accountId) && this.currentStatus == OpenAnswer) {
          this.currentStatus = this.currentStatus.next
          v.alterStars.foreach(
            alterStar =>
              changeAccountStatus(alterStar.accountId,
                                  _.checkAnswer(_ => alterStar.alterStars)))
          noticeEveryone()
        }
      case GoToNextQuestion =>
        if (isParent(accountId) && this.currentStatus == OpenAggregate) {
          this.currentStatus = this.currentStatus.next
          this.children.foreach(account =>
            changeAccountStatus(account.id, _.init()))
          noticeEveryone()
        }
      case other =>
        logger.info(s"RoomId -> $roomId, $other")
    }
//  def changeName(accountId: String, accountName: String): Unit = {
//    changeAccountStatus(accountId, _.rename(accountName))
//  }
}

object QuizRoom {

  def apply(accountId: String, roomName: String)(
      implicit materializer: Materializer): QuizRoom = {
    val roomId = java.util.UUID.randomUUID.toString.replaceAll("-", "")
    new QuizRoom(roomId, roomName)(materializer) {
      override var parent: Account = Account.apply(accountId, Admin)
    }
  }

  def apply(accountId: String, roomId: String, roomName: String)(
      implicit materializer: Materializer): QuizRoom = {
    new QuizRoom(roomId, roomName)(materializer) {
      override var parent: Account = Account.apply(accountId, Admin)
    }
  }
}
