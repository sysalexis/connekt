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
package com.flipkart.connekt.barklice

import java.util.concurrent.atomic.AtomicBoolean

import com.flipkart.connekt.commons.connections.ConnectionProvider
import com.flipkart.connekt.commons.core.BaseApp
import com.flipkart.connekt.commons.dao.DaoFactory
import com.flipkart.connekt.commons.factories.{ConnektLogger, LogFile}
import com.flipkart.connekt.commons.services.ConnektConfig
import com.flipkart.connekt.commons.utils.{NetworkUtils, StringUtils, ConfigUtils}
import flipkart.cp.convert.ha.worker.Bootstrap

object BarkLiceBoot extends BaseApp {

  private val initialized = new AtomicBoolean(false)

  def start() {
    if (!initialized.getAndSet(true)) {
      ConnektLogger(LogFile.SERVICE).info("BarkLiceBoot initializing.")

      val loggerConfigFile = ConfigUtils.getSystemProperty("log4j.configurationFile").getOrElse("log4j2-barklice.xml")

      ConnektLogger(LogFile.SERVICE).info(s"BarkLiceBoot logging using: $loggerConfigFile")
      ConnektLogger.init(loggerConfigFile)

      val applicationConfigFile = ConfigUtils.getSystemProperty("barklice.appConfigurationFile").getOrElse("barklice-config.json")
      ConnektConfig(configServiceHost, configServicePort, apiVersion)(Seq("fk-connekt-root", "fk-connekt-".concat(ConfigUtils.getConfEnvironment), "fk-connekt-barklice"))(applicationConfigFile)

      DaoFactory.setUpConnectionProvider(new ConnectionProvider)

      val hConfig = ConnektConfig.getConfig("connections.hbase")
      DaoFactory.initHTableDaoFactory(hConfig.get)

      val hostname = NetworkUtils.getHostname
      val instanceId = hostname + "-" + StringUtils.generateRandomStr(5)
      ConnektLogger(LogFile.SERVICE).info(s"Starting BarkLice with InstanceId: $instanceId, Hostname : $hostname ...")
      new Bootstrap(instanceId, "", hostname).start()
    }
  }

  def terminate() = {
    ConnektLogger(LogFile.SERVICE).info("BarkLiceBoot shutting down")
    if (initialized.get()) {
      DaoFactory.shutdownHTableDaoFactory()
      ConnektLogger.shutdown()
    }
  }

  def main(args: Array[String]) {
    System.setProperty("log4j.configurationFile", "log4j2-test.xml")
    start()

  }
}

