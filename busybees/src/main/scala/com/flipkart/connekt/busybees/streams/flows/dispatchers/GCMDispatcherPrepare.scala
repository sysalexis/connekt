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

import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import com.flipkart.connekt.busybees.models.GCMRequestTracker
import com.flipkart.connekt.busybees.streams.errors.ConnektPNStageException
import com.flipkart.connekt.busybees.streams.flows.MapFlowStage
import com.flipkart.connekt.commons.entities.MobilePlatform
import com.flipkart.connekt.commons.factories.{ConnektLogger, LogFile}
import com.flipkart.connekt.commons.iomodels.GCMPayloadEnvelope
import com.flipkart.connekt.commons.iomodels.MessageStatus.InternalStatus
import com.flipkart.connekt.commons.services.KeyChainManager
import com.flipkart.connekt.commons.utils.StringUtils._

class GCMDispatcherPrepare extends MapFlowStage[GCMPayloadEnvelope, (HttpRequest, GCMRequestTracker)] {

  override implicit val map: GCMPayloadEnvelope => List[(HttpRequest, GCMRequestTracker)] = message => {
    try {
      ConnektLogger(LogFile.PROCESSORS).debug("GCMDispatcherPrepare received message: {}", supplier(message.messageId))
      ConnektLogger(LogFile.PROCESSORS).trace("GCMDispatcherPrepare received message: {}", supplier(message))

      val requestEntity = HttpEntity(ContentTypes.`application/json`, message.gcmPayload.getJson)
      val requestHeaders = scala.collection.immutable.Seq[HttpHeader](RawHeader("Authorization", "key=" + KeyChainManager.getGoogleCredential(message.appName).get.apiKey))
      val httpRequest = HttpRequest(HttpMethods.POST, "/fcm/send", requestHeaders, requestEntity)
      val requestTrace = GCMRequestTracker(message.messageId, message.clientId, message.deviceId, message.appName, message.contextId, message.meta)

      List(httpRequest -> requestTrace)
    } catch {
      case e: Throwable =>
        ConnektLogger(LogFile.PROCESSORS).error(s"GCMDispatcherPrepare failed with ${e.getMessage}", e)
        throw new ConnektPNStageException(message.messageId, message.clientId, message.deviceId.toSet, InternalStatus.StageError, message.appName, MobilePlatform.ANDROID, message.contextId, message.meta, s"GCMDispatcherPrepare-${e.getMessage}", e)
    }
  }
}
