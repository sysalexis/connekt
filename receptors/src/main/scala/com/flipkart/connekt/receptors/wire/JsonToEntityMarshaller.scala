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
package com.flipkart.connekt.receptors.wire

import akka.http.scaladsl.marshalling.{PredefinedToEntityMarshallers, ToEntityMarshaller}
import akka.http.scaladsl.model.MediaTypes
import akka.http.scaladsl.unmarshalling.{FromEntityUnmarshaller, PredefinedFromEntityUnmarshallers}
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import GenericJsonSupport._
/**
 * Derives on [[akka.http.scaladsl.marshalling.PredefinedToEntityMarshallers]] and [[akka.http.scaladsl.marshalling.PredefinedToEntityMarshallers]]
 * to provide implicit generic json un/marshallers. As per akka-http documentation `akka-http-spray-json`
 * module can be used along-with RootJsonReader/RootJsonWriter implementations for every model type T. <br>
 *
 * This however, relies on [[https://github.com/FasterXML/jackson-module-scala scala-jackson]]
 * and hence, not using [[http://doc.akka.io/docs/akka-stream-and-http-experimental/2.0-M1/scala/http/common/json-support.html akka-http-spray-json]]
 * <br>
 *
 * Gist Attribution [[https://gist.github.com/chadselph Chad Selph]]
 * @see
 * http://doc.akka.io/docs/akka-stream-and-http-experimental/2.0-M1/scala/http/common/marshalling.html
 * http://doc.akka.io/docs/akka-stream-and-http-experimental/2.0-M1/scala/http/common/unmarshalling.html
 *
 */
object GenericJsonSupport {

  val jacksonModules = Seq(DefaultScalaModule)

  val mapper = new ObjectMapper() with ScalaObjectMapper
  mapper.registerModules(jacksonModules: _*)
}

trait JsonToEntityMarshaller extends PredefinedToEntityMarshallers {

  implicit def genericMarshaller[T <: AnyRef]: ToEntityMarshaller[T] =
    stringMarshaller(MediaTypes.`application/json`)
      .compose[T](mapper.writeValueAsString)
}

trait JsonFromEntityUnmarshaller extends PredefinedFromEntityUnmarshallers {

  implicit def genericUnmarshaller[T: Manifest]: FromEntityUnmarshaller[T] =
    stringUnmarshaller.forContentTypes(MediaTypes.`application/json`)
      .map(mapper.readValue[T])
}
