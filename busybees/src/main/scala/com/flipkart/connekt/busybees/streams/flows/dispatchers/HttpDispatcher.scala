package com.flipkart.connekt.busybees.streams.flows.dispatchers

import java.net.URL

import akka.http.javadsl.model.HttpEntityStrict
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.stream._
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import com.flipkart.connekt.busybees.BusyBeesBoot
import com.flipkart.connekt.commons.factories.{ConnektLogger, LogFile}
import com.flipkart.connekt.commons.utils.StringUtils

import scala.concurrent.Future
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

/**
 * Created by kinshuk.bairagi on 02/02/16.
 */
class HttpDispatcher[V: ClassTag](uri: URL, method: HttpMethod, headers: scala.collection.immutable.Seq[HttpHeader], payloadCreator: (V) => RequestEntity) extends GraphStage[FlowShape[V, Try[HttpResponse]]] {

  val in = Inlet[V]("HttpDispatcher.In")
  val out = Outlet[Try[HttpResponse]]("HttpDispatcher.Out")

  override def shape: FlowShape[V, Try[HttpResponse]] = FlowShape.of(in, out)

  implicit val system = BusyBeesBoot.system
  implicit val ec = BusyBeesBoot.system.dispatcher
  implicit val mat = BusyBeesBoot.mat

  lazy implicit val poolClientFlow = Http().cachedHostConnectionPoolTls[String](uri.getHost, uri.getPort)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {

    setHandler(in, new InHandler {

      override def onPush(): Unit = try {

        val message = grab(in)
        ConnektLogger(LogFile.PROCESSORS).info(s"HttpDispatcher:: onPush:: Received Message: ${message.toString}")

        val request = new HttpRequest(method, uri.getPath,
          headers, payloadCreator(message)
        )

        val requestId = StringUtils.generateRandomStr(10)

        ConnektLogger(LogFile.PROCESSORS).info(s"HttpDispatcher:: onPush:: Request Payload : ${request.entity.asInstanceOf[HttpEntityStrict].data().decodeString("UTF-8")}")

        val responseFuture: Future[(Try[HttpResponse], String)] = Source.single(request -> requestId)
          .via(poolClientFlow).runWith(Sink.head)

        responseFuture.onComplete {
          case Success(tr) =>
            ConnektLogger(LogFile.PROCESSORS).error(s"HttpDispatcher:: onPush:: Relayed Try[HttpResponse] to next stage for: ${tr._2}")
            push(out, tr._1)
          case Failure(ft) =>
            ConnektLogger(LogFile.PROCESSORS).error(s"HttpDispatcher:: onPush:: ResponseFuture Failure ${ft.getMessage}", ft)
        }
      } catch {
        case e: Throwable =>
          ConnektLogger(LogFile.PROCESSORS).error(s"HttpDispatcher:: onPush :: Error", e)
      }

    })

    setHandler(out, new OutHandler {
      override def onPull(): Unit = {
        ConnektLogger(LogFile.PROCESSORS).info(s"HttpDispatcher:: onPull")
        pull(in)
      }
    })

  }

}