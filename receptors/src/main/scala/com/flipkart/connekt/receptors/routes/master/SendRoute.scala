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
package com.flipkart.connekt.receptors.routes.master

import akka.connekt.AkkaHelpers._
import akka.http.scaladsl.model.StatusCodes
import akka.stream.ActorMaterializer
import com.fasterxml.jackson.databind.node.ObjectNode
import com.flipkart.connekt.commons.entities.MobilePlatform.MobilePlatform
import com.flipkart.connekt.commons.entities.{Channel, MobilePlatform}
import com.flipkart.connekt.commons.factories.{ConnektLogger, LogFile, ServiceFactory}
import com.flipkart.connekt.commons.helpers.ConnektRequestHelper._
import com.flipkart.connekt.commons.iomodels.MessageStatus.InternalStatus
import com.flipkart.connekt.commons.iomodels._
import com.flipkart.connekt.commons.services.{DeviceDetailsService, ExclusionService}
import com.flipkart.connekt.commons.utils.StringUtils._
import com.flipkart.connekt.receptors.directives.MPlatformSegment
import com.flipkart.connekt.receptors.routes.BaseJsonHandler
import com.flipkart.connekt.receptors.wire.ResponseUtils._
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat

import scala.collection.mutable.ListBuffer
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

class SendRoute(implicit am: ActorMaterializer) extends BaseJsonHandler {

  lazy implicit val stencilService = ServiceFactory.getStencilService
  implicit val ioDispatcher = am.getSystem.dispatchers.lookup("akka.actor.route-blocking-dispatcher")

  val route =
    authenticate {
      user =>
        pathPrefix("v1") {
          pathPrefix("send" / "push") {
            path(MPlatformSegment / Segment) {
              (appPlatform: MobilePlatform, appName: String) =>
                authorize(user, "SEND_PN", "SEND_" + appName) {
                  idempotentRequest(appName) {
                    post {
                      getXHeaders { headers =>
                        entity(as[ConnektRequest]) { r =>
                          isTestRequest { tR =>
                            if (!tR) {
                              complete {
                                Future {
                                  profile(s"sendDevicePush.$appPlatform.$appName") {
                                    val request = r.copy(clientId = user.userId, channel = "push", meta = {
                                      Option(r.meta).getOrElse(Map.empty[String, String]) ++ headers
                                    })
                                    request.validate

                                    ConnektLogger(LogFile.SERVICE).debug(s"Received PN request with payload: ${request.toString}")

                                    val pnRequestInfo = request.channelInfo.asInstanceOf[PNRequestInfo].copy(appName = appName.toLowerCase)

                                    if (pnRequestInfo.deviceIds != null && pnRequestInfo.deviceIds.nonEmpty) {

                                      val groupedPlatformRequests = ListBuffer[ConnektRequest]()

                                      /* Find platform for each deviceId, group */
                                      appPlatform match {
                                        case MobilePlatform.UNKNOWN =>
                                          val groupedDevices = DeviceDetailsService.get(pnRequestInfo.appName, pnRequestInfo.deviceIds).get.groupBy(_.osName).mapValues(_.map(_.deviceId).toSet)
                                          groupedPlatformRequests ++= groupedDevices.map { case (platform, deviceId) =>
                                            request.copy(channelInfo = pnRequestInfo.copy(platform = platform, deviceIds = deviceId))
                                          }
                                        case _ =>
                                          groupedPlatformRequests += request.copy(channelInfo = pnRequestInfo.copy(platform = appPlatform))
                                      }

                                      val failure = ListBuffer(pnRequestInfo.deviceIds.toList.diff(groupedPlatformRequests.flatMap(_.destinations)): _ *)
                                      ServiceFactory.getReportingService.recordPushStatsDelta(user.userId, request.contextId, request.stencilId, Option(appPlatform), appName, InternalStatus.Rejected, failure.size)

                                      val success = scala.collection.mutable.Map[String, Set[String]]()

                                      if (groupedPlatformRequests.nonEmpty) {
                                        val queueName = ServiceFactory.getMessageService(Channel.PUSH).getRequestBucket(request, user)
                                        groupedPlatformRequests.foreach { p =>
                                          /* enqueue multiple requests into kafka */
                                          ServiceFactory.getMessageService(Channel.PUSH).saveRequest(p, queueName, isCrucial = true) match {
                                            case Success(id) =>
                                              val deviceIds = p.channelInfo.asInstanceOf[PNRequestInfo].deviceIds
                                              success += id -> deviceIds
                                              ServiceFactory.getReportingService.recordPushStatsDelta(user.userId, request.contextId, request.stencilId, Option(p.platform), appName, InternalStatus.Received, deviceIds.size)
                                            case Failure(t) =>
                                              val deviceIds = p.channelInfo.asInstanceOf[PNRequestInfo].deviceIds
                                              failure ++= deviceIds
                                              ServiceFactory.getReportingService.recordPushStatsDelta(user.userId, request.contextId, request.stencilId, Option(p.platform), appName, InternalStatus.Rejected, deviceIds.size)
                                          }
                                        }
                                        GenericResponse(StatusCodes.Created.intValue, null, SendResponse("PN Send Request Received", success.toMap, failure.toList)).respond
                                      } else {
                                        GenericResponse(StatusCodes.BadRequest.intValue, null, SendResponse("No valid devices found", success.toMap, failure.toList)).respond
                                      }
                                    } else {
                                      ConnektLogger(LogFile.SERVICE).error(s"Request Validation Failed, $request ")
                                      GenericResponse(StatusCodes.BadRequest.intValue, null, Response("Request Validation Failed, Please ensure mandatory field values.", null)).respond
                                    }
                                  }
                                }(ioDispatcher)
                              }
                            } else {
                              complete(GenericResponse(StatusCodes.Created.intValue, null, SendResponse(s"PN Perf Send Request Received. Skipped sending PN for device ${r.destinations.mkString(",")}.", Map("fake_message_id" -> r.destinations), null)).respond)
                            }
                          }
                        }
                      }
                    }
                  }
                }
            } ~ path(MPlatformSegment / Segment / "users" / Segment) {
              (appPlatform: MobilePlatform, appName: String, userId: String) =>
                authorize(user, "SEND_PN", "SEND_" + appName) {
                  idempotentRequest(appName) {
                    post {
                      getXHeaders { headers =>
                        entity(as[ConnektRequest]) { r =>
                          isTestRequest { tR =>
                            if (!tR) {
                              complete {
                                Future {
                                  profile(s"sendUserPush.$appPlatform.$appName") {
                                    val request = r.copy(clientId = user.userId, channel = "push", meta = {
                                      Option(r.meta).getOrElse(Map.empty[String, String]) ++ headers
                                    })
                                    request.validate

                                    ConnektLogger(LogFile.SERVICE).debug(s"Received PN request sent for user : $userId with payload: ${request.toString}")

                                    val pnRequestInfo = request.channelInfo.asInstanceOf[PNRequestInfo].copy(appName = appName.toLowerCase)
                                    val groupedPlatformRequests = ListBuffer[ConnektRequest]()

                                    appPlatform match {
                                      case MobilePlatform.UNKNOWN =>
                                        val groupedDevices = DeviceDetailsService.getByUserId(appName.toLowerCase, userId).get.groupBy(_.osName).mapValues(_.map(_.deviceId).toSet)
                                        groupedPlatformRequests ++= groupedDevices.map { case (platform, deviceId) =>
                                          platform -> request.copy(channelInfo = pnRequestInfo.copy(platform = platform, deviceIds = deviceId))
                                        }.values
                                      case _ =>
                                        val osSpecificDeviceIds = DeviceDetailsService.getByUserId(appName.toLowerCase, userId).get.filter(_.osName == appPlatform.toLowerCase).map(_.deviceId).toSet
                                        if (osSpecificDeviceIds.nonEmpty)
                                          groupedPlatformRequests += request.copy(channelInfo = pnRequestInfo.copy(platform = appPlatform, deviceIds = osSpecificDeviceIds))
                                    }

                                    val failure = ListBuffer[String]()
                                    val success = scala.collection.mutable.Map[String, Set[String]]()

                                    if (groupedPlatformRequests.nonEmpty) {
                                      val queueName = ServiceFactory.getMessageService(Channel.PUSH).getRequestBucket(request, user)

                                      groupedPlatformRequests.foreach { p =>
                                        ServiceFactory.getMessageService(Channel.PUSH).saveRequest(p, queueName, isCrucial = true) match {
                                          case Success(id) =>
                                            val deviceIds = p.channelInfo.asInstanceOf[PNRequestInfo].deviceIds
                                            success += id -> deviceIds
                                            ServiceFactory.getReportingService.recordPushStatsDelta(user.userId, request.contextId, request.stencilId, Option(p.platform), appName, InternalStatus.Received, deviceIds.size)
                                          case Failure(t) =>
                                            val deviceIds = p.channelInfo.asInstanceOf[PNRequestInfo].deviceIds
                                            failure ++= deviceIds
                                            ServiceFactory.getReportingService.recordPushStatsDelta(user.userId, request.contextId, request.stencilId, Option(p.platform), appName, InternalStatus.Rejected, deviceIds.size)
                                        }
                                      }

                                      GenericResponse(StatusCodes.Created.intValue, null, SendResponse(s"PN request processed for user $userId.", success.toMap, failure.toList)).respond
                                    } else {
                                      GenericResponse(StatusCodes.NotFound.intValue, null, Response(s"No device Found for user: $userId.", null)).respond
                                    }
                                  }
                                }(ioDispatcher)
                              }
                            } else {
                              complete(GenericResponse(StatusCodes.Created.intValue, null, SendResponse(s"PN Perf Send Request Received. Skipped sending PN for user $userId.", Map("fake_message_id" -> Set(userId)), null)).respond)
                            }
                          }
                        }
                      }
                    }
                  }
                }
            }
          } ~ pathPrefix("send" / "email") {
            path(Segment) { appName: String =>
              authorize(user, "SEND_EMAIL", s"SEND_$appName") {
                idempotentRequest(appName) {
                  post {
                    getXHeaders { headers =>
                      entity(as[ConnektRequest]) { r =>
                        isTestRequest { tR =>
                          if (!tR) {
                            complete {
                              Future {
                                profile("email") {
                                  val request = r.copy(clientId = user.userId, channel = "email", meta = {
                                    Option(r.meta).getOrElse(Map.empty[String, String]) ++ headers
                                  })
                                  request.validate

                                  ConnektLogger(LogFile.SERVICE).debug(s"Received EMAIL request with payload: ${request.toString}")

                                  val emailRequestInfo = request.channelInfo.asInstanceOf[EmailRequestInfo].copy(appName = appName.toLowerCase).toStrict

                                  if (emailRequestInfo.to != null && emailRequestInfo.to.nonEmpty) {

                                    val success = scala.collection.mutable.Map[String, Set[String]]()

                                    val queueName = ServiceFactory.getMessageService(Channel.EMAIL).getRequestBucket(request, user)
                                    val recipients = emailRequestInfo.to.map(_.address) ++ emailRequestInfo.cc.map(_.address) ++ emailRequestInfo.bcc.map(_.address)

                                    //TODO: do an exclusion check
                                    val excludedAddress = recipients.filterNot { address => ExclusionService.lookup(request.channel, appName, address).getOrElse(false) }
                                    val failure = ListBuffer[String](excludedAddress.toSeq :_*)
                                    val validRecipients = recipients.diff(excludedAddress)

                                    if(validRecipients.nonEmpty) {
                                      /* enqueue multiple requests into kafka */
                                      ServiceFactory.getMessageService(Channel.EMAIL).saveRequest(request.copy(channelInfo = emailRequestInfo), queueName, isCrucial = true) match {
                                        case Success(id) =>
                                          success += id -> validRecipients
                                          ServiceFactory.getReportingService.recordChannelStatsDelta(user.userId, request.contextId, request.stencilId, Channel.EMAIL, appName, InternalStatus.Received, recipients.size)
                                        case Failure(t) =>
                                          failure ++= validRecipients
                                          ServiceFactory.getReportingService.recordChannelStatsDelta(user.userId, request.contextId, request.stencilId, Channel.EMAIL, appName, InternalStatus.Rejected, recipients.size)
                                      }

                                      GenericResponse(StatusCodes.Accepted.intValue, null, SendResponse("Email Send Request Received", success.toMap, failure.toList)).respond
                                    } else {
                                      GenericResponse(StatusCodes.BadRequest.intValue, null, SendResponse(s"No valid destinations found", success.toMap, failure.toList)).respond
                                    }
                                  } else {
                                    ConnektLogger(LogFile.SERVICE).error(s"Request Validation Failed, $request ")
                                    GenericResponse(StatusCodes.BadRequest.intValue, null, Response("Request Validation Failed, Please ensure mandatory field values.", null)).respond
                                  }
                                }
                              }(ioDispatcher)
                            }
                          } else {
                            complete(GenericResponse(StatusCodes.Accepted.intValue, null, SendResponse(s"Email Perf Send Request Received. Skipped sending email for address ${r.destinations.mkString(",")}.", Map("fake_message_id" -> r.destinations), null)).respond)
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
          } ~ pathPrefix("send" / "sms") {
            path(Segment) {
              (appName: String) =>
                authorize(user, "SEND_SMS", "SEND_" + appName) {
                  idempotentRequest(appName) {
                    post {
                      getXHeaders { headers =>
                        entity(as[ConnektRequest]) { r =>
                          isTestRequest { tR =>
                            if (!tR) {
                              complete {
                                Future {
                                  profile(s"sendSms.$appName") {
                                    val request = r.copy(clientId = user.userId, channel = Channel.SMS.toString, meta = {
                                      Option(r.meta).getOrElse(Map.empty[String, String]) ++ headers
                                    })
                                    request.validate

                                    val appLevelConfigService = ServiceFactory.getUserProjectConfigService
                                    ConnektLogger(LogFile.SERVICE).debug(s"Received SMS request with payload: ${request.toString}")
                                    val smsRequestInfo = request.channelInfo.asInstanceOf[SmsRequestInfo].copy(appName = appName.toLowerCase)

                                    val appDefaultCountryCode = appLevelConfigService.getProjectConfiguration(appName.toLowerCase, "app-local-country-code").get.get.value.getObj[ObjectNode]
                                    val phoneUtil: PhoneNumberUtil = PhoneNumberUtil.getInstance()

                                    val validNumbers = ListBuffer[String]()
                                    val invalidNumbers = ListBuffer[String]()

                                    if (smsRequestInfo.receivers != null && smsRequestInfo.receivers.nonEmpty) {

                                      smsRequestInfo.receivers.foreach(r => {
                                        val validateNum = Try(phoneUtil.parse(r, appDefaultCountryCode.get("localRegion").asText.trim.toUpperCase))
                                        if (validateNum.isSuccess && phoneUtil.isValidNumber(validateNum.get)) {
                                          validNumbers += phoneUtil.format(validateNum.get, PhoneNumberFormat.E164)
                                        } else {
                                          ConnektLogger(LogFile.PROCESSORS).error(s"Dropping invalid numbers: $r")
                                          ServiceFactory.getReportingService.recordChannelStatsDelta(request.clientId, request.contextId, request.stencilId, Channel.SMS, appName, InternalStatus.Rejected)
                                          invalidNumbers += r
                                        }
                                      })

                                      val smsRequest = request.copy(channelInfo = smsRequestInfo.copy(receivers = validNumbers.toSet))

                                      val queueName = ServiceFactory.getMessageService(Channel.SMS).getRequestBucket(request, user)
                                      /* enqueue multiple requests into kafka */
                                      val (success, failure) = ServiceFactory.getMessageService(Channel.SMS).saveRequest(smsRequest, queueName, isCrucial = true) match {
                                        case Success(id) =>
                                          (Map(id -> validNumbers.toSet), invalidNumbers)
                                        case Failure(t) =>
                                          (Map(), smsRequestInfo.receivers)
                                      }
                                      GenericResponse(StatusCodes.Accepted.intValue, null, SendResponse("SMS Send Request Received", success.toMap, failure.toList)).respond
                                    }
                                    else {
                                      ConnektLogger(LogFile.SERVICE).error(s"Request Validation Failed, $request ")
                                      GenericResponse(StatusCodes.BadRequest.intValue, null, Response("Request Validation Failed, Please ensure mandatory field values.", null)).respond
                                    }
                                  }
                                }(ioDispatcher)
                              }
                            } else {
                              complete(GenericResponse(StatusCodes.Accepted.intValue, null, SendResponse(s"Sms Perf Send Request Received. Skipped sending Sms for numbers ${r.destinations.mkString(",")}.", Map("fake_message_id" -> r.destinations), null)).respond)
                            }
                          }
                        }
                      }
                    }
                  }
                }
            }
          }
        }
    }
}
