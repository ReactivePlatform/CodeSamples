/*
 * Copyright (c) 2018 https://www.reactivedesignpatterns.com/
 *
 * Copyright (c) 2018 https://rdp.reactiveplatform.xyz/
 *
 */

package chapter15

import akka.actor.Scheduler
import akka.pattern.AskTimeoutException
import akka.typed.AskPattern._
import akka.typed.ScalaDSL._
import akka.typed._
import akka.util.Timeout

import scala.concurrent.duration._

object Aggregator {

  final case class GetTheme(user: String, replyTo: ActorRef[ThemeResult])

  final case class ThemeResult(css: String)

  final case class GetPersonalNews(user: String, replyTo: ActorRef[PersonalNewsResult])

  final case class PersonalNewsResult(news: List[String])

  final case class GetTopNews(replyTo: ActorRef[TopNewsResult])

  final case class TopNewsResult(news: List[String])

  final case class GetFrontPage(user: String, replyTo: ActorRef[FrontPageResult])

  final case class FrontPageResult(user: String, css: String, news: List[String])

  final case class GetOverride(replyTo: ActorRef[OverrideResult])

  sealed trait OverrideResult

  case object NoOverride extends OverrideResult

  final case class Override(css: String, news: List[String]) extends OverrideResult

  //#snip_15-16
  class FrontPageResultBuilder(user: String) {
    private var css: Option[String] = None
    private var personalNews: Option[List[String]] = None
    private var topNews: Option[List[String]] = None

    def addCSS(css: String): Unit = this.css = Option(css)

    def addPersonalNews(news: List[String]): Unit =
      this.personalNews = Option(news)

    def addTopNews(news: List[String]): Unit =
      this.topNews = Option(news)

    def timeout(): Unit = {
      if (css.isEmpty) css = Some("default.css")
      if (personalNews.isEmpty) personalNews = Some(Nil)
      if (topNews.isEmpty) topNews = Some(Nil)
    }

    def isComplete: Boolean =
      css.isDefined &&
      personalNews.isDefined && topNews.isDefined

    def result: FrontPageResult = {
      val topSet = topNews.get.toSet
      val allNews = topNews.get :::
        personalNews.get.filterNot(topSet.contains)
      FrontPageResult(user, css.get, allNews)
    }
  }

  //#snip_15-16

  //#snip_15-15
  private def pf(p: PartialFunction[AnyRef, Unit]): p.type = p

  def frontPage(
      themes: ActorRef[GetTheme],
      personalNews: ActorRef[GetPersonalNews],
      topNews: ActorRef[GetTopNews]): Behavior[GetFrontPage] =
    ContextAware { ctx =>
      Static {
        case GetFrontPage(user, replyTo) =>
          val childRef = ctx.spawnAnonymous(Deferred { () =>
            val builder = new FrontPageResultBuilder(user)
            Partial[AnyRef](pf {
              case ThemeResult(css)         => builder.addCSS(css)
              case PersonalNewsResult(news) => builder.addPersonalNews(news)
              case TopNewsResult(news)      => builder.addTopNews(news)
              case ReceiveTimeout           => builder.timeout()
            }.andThen { _ =>
              if (builder.isComplete) {
                replyTo ! builder.result
                Stopped
              } else Same
            })
          })
          themes ! GetTheme(user, childRef)
          personalNews ! GetPersonalNews(user, childRef)
          topNews ! GetTopNews(childRef)
          ctx.schedule(1.second, childRef, ReceiveTimeout)
      }
    }

  //#snip_15-15

  //#snip_15-14
  def futureFrontPage(
      themes: ActorRef[GetTheme],
      personalNews: ActorRef[GetPersonalNews],
      topNews: ActorRef[GetTopNews]): Behavior[GetFrontPage] =
    ContextAware { ctx =>
      import ctx.executionContext
      implicit val timeout: Timeout = Timeout(1.second)
      implicit val scheduler: Scheduler = ctx.system.scheduler

      Static {
        case GetFrontPage(user, replyTo) =>
          val cssFuture =
            (themes ? (GetTheme(user, _: ActorRef[ThemeResult]))).map(_.css).recover {
              case _: AskTimeoutException => "default.css"
            }
          val personalNewsFuture =
            (personalNews ? (GetPersonalNews(user, _: ActorRef[PersonalNewsResult]))).map(_.news).recover {
              case _: AskTimeoutException => Nil
            }
          val topNewsFuture =
            (topNews ? (GetTopNews(_: ActorRef[TopNewsResult]))).map(_.news).recover {
              case _: AskTimeoutException => Nil
            }
          for {
            css <- cssFuture
            personalNews <- personalNewsFuture
            topNews <- topNewsFuture
          } {
            val topSet = topNews.toSet
            val allNews = topNews ::: personalNews.filterNot(topSet.contains)
            replyTo ! FrontPageResult(user, css, allNews)
          }
      }
    }

  //#snip_15-14

  def futureFrontPageWithOverride(
      themes: ActorRef[GetTheme],
      personalNews: ActorRef[GetPersonalNews],
      topNews: ActorRef[GetTopNews],
      overrides: ActorRef[GetOverride]): Behavior[GetFrontPage] =
    ContextAware { ctx =>
      import ctx.executionContext
      implicit val timeout: Timeout = Timeout(1.second)
      implicit val scheduler: Scheduler = ctx.system.scheduler

      Static {
        case GetFrontPage(user, replyTo) =>
          val cssFuture =
            (themes ? (GetTheme(user, _: ActorRef[ThemeResult]))).map(_.css).recover {
              case _: AskTimeoutException => "default.css"
            }
          val personalNewsFuture =
            (personalNews ? (GetPersonalNews(user, _: ActorRef[PersonalNewsResult]))).map(_.news).recover {
              case _: AskTimeoutException => Nil
            }
          val topNewsFuture =
            (topNews ? (GetTopNews(_: ActorRef[TopNewsResult]))).map(_.news).recover {
              case _: AskTimeoutException => Nil
            }

          //#snip_15-17
          val overrideFuture =
            (overrides ? (GetOverride(_: ActorRef[OverrideResult]))).recover {
              case _: AskTimeoutException => NoOverride
            }
          for {
            css <- cssFuture
            personalNews <- personalNewsFuture
            topNews <- topNewsFuture
            ovr <- overrideFuture
          } ovr match {
            case NoOverride =>
              val topSet = topNews.toSet
              val allNews = topNews ::: personalNews.filterNot(topSet.contains)
              replyTo ! FrontPageResult(user, css, allNews)
            case _ => // nothing to do here
          }
          for {
            ovr <- overrideFuture
          } ovr match {
            case NoOverride => // nothing to do here
            case Override(css, news) =>
              replyTo ! FrontPageResult(user, css, news)
          }
        //#snip_15-17

      }
    }

}
