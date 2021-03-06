/*
 *         -╥⌐⌐⌐⌐            -⌐⌐⌐⌐-
 *      ≡╢░░░░⌐\░░░φ     ╓╝░░░░⌐░░░░╪╕
 *     ╣╬░░`    `░░░╢┘ φ▒╣╬╝╜     ░░╢╣Q
 *    ║╣╬░⌐        ` ╤▒▒▒Å`        ║╢╬╣
 *    ╚╣╬░⌐        ╔▒▒▒▒`«╕        ╢╢╣▒
 *     ╫╬░░╖    .░ ╙╨╨  ╣╣╬░φ    ╓φ░╢╢Å
 *      ╙╢░░░░⌐"░░░╜     ╙Å░░░░⌐░░░░╝`
 *        ``˚¬ ⌐              ˚˚⌐´
 *
 *      Copyright © 2016 Flipkart.com
 */
package com.flipkart.connekt.busybees.streams.flows.dispatchers

import java.util.concurrent.{ConcurrentHashMap, TimeUnit}

import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.Flow
import com.flipkart.connekt.busybees.models.APNSRequestTracker
import com.flipkart.connekt.commons.factories.{ConnektLogger, LogFile}
import com.flipkart.connekt.commons.metrics.Instrumented
import com.flipkart.connekt.commons.services.{ConnektConfig, KeyChainManager}
import com.flipkart.connekt.commons.utils.FutureUtils._
import com.flipkart.connekt.commons.utils.StringUtils
import com.google.common.util.concurrent.ThreadFactoryBuilder
import com.relayrides.pushy.apns.util.SimpleApnsPushNotification
import com.relayrides.pushy.apns._
import com.relayrides.pushy.apns.metrics.dropwizard.DropwizardApnsClientMetricsListener
import io.netty.channel.nio.NioEventLoopGroup

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContextExecutor, Future, Promise}
import scala.util.Try
import scala.util.control.NonFatal

import com.flipkart.connekt.commons.core.Wrappers._

object APNSDispatcher extends Instrumented {

  private val apnsHost: String = ConnektConfig.getOrElse("ios.apns.hostname", ApnsClient.PRODUCTION_APNS_HOST)
  private[busybees] val clientGatewayCache = new ConcurrentHashMap[String, Future[ApnsClient]]

  private def createAPNSClient(appName: String): ApnsClient = {
    ConnektLogger(LogFile.PROCESSORS).info(s"APNSDispatcher starting $appName apns-client")
    val credential = KeyChainManager.getAppleCredentials(appName).get
    //TODO: shutdown this eventloop when client is closed.
    val eventLoop = new NioEventLoopGroup(4, new ThreadFactoryBuilder().setNameFormat(s"apns-nio-$appName-${StringUtils.generateRandomStr(4)}-%s").build())

    val metricsListener = new DropwizardApnsClientMetricsListener()
    Try_(registry.register(getMetricName(appName), metricsListener))

    val client = new ApnsClientBuilder()
      .setClientCredentials(credential.getCertificateFile, credential.passkey)
      .setEventLoopGroup(eventLoop)
      .setIdlePingInterval(25, TimeUnit.SECONDS)
      .setMetricsListener(metricsListener)
      .build()
    client.connect(apnsHost).await(60, TimeUnit.SECONDS)
    if (!client.isConnected)
      ConnektLogger(LogFile.PROCESSORS).error(s"APNSDispatcher Unable to connect [$appName] apns-client in 2minutes")
    client
  }

  private[busybees] def cachedGateway(appName: String): Future[ApnsClient] = {
    val gatewayPromise = Promise[ApnsClient]()
    clientGatewayCache.putIfAbsent(appName, gatewayPromise.future) match {
      case null ⇒ // only one thread can get here at a time
        val gateway =
        //TODO: Move to a pooled client(as recommended by apns)
          try createAPNSClient(appName)
          catch {
            case NonFatal(e) =>
              clientGatewayCache.remove(appName)
              registry.remove(getMetricName(appName))
              gatewayPromise.failure(e)
              throw e
          }
        val fastFuture = FastFuture.successful(gateway)
        clientGatewayCache.put(appName, fastFuture) // optimize subsequent gateway accesses
        gatewayPromise.success(gateway) // satisfy everyone who got a hold of our promise while we were starting up
        fastFuture
      case future ⇒ future // return cached instance
    }
  }

  sys.addShutdownHook {
    // Disconnect all the APNSClients prior to stop
    clientGatewayCache.asScala.foreach(kv => {
      ConnektLogger(LogFile.PROCESSORS).info(s"APNSDispatcher stopping ${kv._1} apns-client")
      kv._2.map(_.disconnect().awaitUninterruptibly(5000))(scala.concurrent.ExecutionContext.Implicits.global)
    })
  }


}

class APNSDispatcher(parallelism: Int)(implicit ec: ExecutionContextExecutor) {

  import com.flipkart.connekt.busybees.streams.flows.dispatchers.APNSDispatcher._

  def flow = {

    Flow[(SimpleApnsPushNotification, APNSRequestTracker)].mapAsyncUnordered(parallelism) {
      case (request, userContext) ⇒
        val result = Promise[(Try[PushNotificationResponse[SimpleApnsPushNotification]], APNSRequestTracker)]()

        val gatewayFuture = cachedGateway(userContext.appName)

        gatewayFuture
          .flatMap(client => client.sendNotification(request).asScala.recoverWith {
            case nce: ClientNotConnectedException =>
              ConnektLogger(LogFile.PROCESSORS).info("APNSDispatcher waiting for apns-client to reconnect")
              client.getReconnectionFuture.awaitUninterruptibly()
              ConnektLogger(LogFile.PROCESSORS).info(s"APNSDispatcher apns-client reconnected with status [${client.isConnected}]")
              if (!client.isConnected) {
                ConnektLogger(LogFile.PROCESSORS).warn(s"APNSDispatcher apns-client reconnect error", client.getReconnectionFuture.cause())
                clientGatewayCache.remove(userContext.appName)
                ConnektLogger(LogFile.PROCESSORS).info(s"APNSDispatcher apns-client destroyed ${userContext.appName}, since reconnect failed.")
              }
              //client.sendNotification(request).asScala //TODO: Observe number of errors and then enable retry if required.
              FastFuture.failed(nce)
          })(ec)
          .onComplete(responseTry ⇒ result.success(responseTry -> userContext))(ec)
        result.future
    }
  }

}
