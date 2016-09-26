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
package com.flipkart.connekt.commons.helpers

import com.flipkart.connekt.commons.iomodels.{PNRequestInfo, ConnektRequest}
import com.flipkart.connekt.commons.utils.StringUtils
import com.flipkart.connekt.commons.utils.StringUtils._

object XmppMessageIdHelper {
  val deviceIdText = "DEVICE_ID"
  val messageIdText = "MESSAGE_ID"
  val clientIdText = "CLIENT_ID"
  val contextIdText = "CONTEXT_ID"
  val appIdText = "APP_ID"
  def generateMessageId(message: ConnektRequest, deviceId:String):String =
    message.id + ":" + deviceId + ":" + message.clientId + ":" + message.channelInfo.asInstanceOf[PNRequestInfo].appName + ":" + message.contextId.orEmpty

  def parseMessageId(messageStr:String):Map[String,String] = {
    if (StringUtils.isNullOrEmpty(messageStr)) Map (deviceIdText -> org.apache.commons.lang.StringUtils.EMPTY,
      messageIdText -> org.apache.commons.lang.StringUtils.EMPTY,
      clientIdText -> org.apache.commons.lang.StringUtils.EMPTY,
      contextIdText -> org.apache.commons.lang.StringUtils.EMPTY)
    else {
      val splitString = messageStr.split(':')
      val context = if ( splitString.length >= 5) splitString(4) else org.apache.commons.lang.StringUtils.EMPTY
      Map (deviceIdText -> splitString(1),
        messageIdText -> splitString(0),
        clientIdText -> splitString(2),
        appIdText -> splitString(3),
        contextIdText -> context)
    }
  }
}