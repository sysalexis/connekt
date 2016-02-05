package com.flipkart.connekt.receptors.routes

import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer
import com.flipkart.connekt.receptors.directives.AuthenticationDirectives
import com.flipkart.connekt.receptors.routes.Stencils.StencilsRoute
import com.flipkart.connekt.receptors.routes.callbacks.Callback
import com.flipkart.connekt.receptors.routes.push.{Fetch, LdapAuthentication, Registration, Unicast}
import com.flipkart.connekt.receptors.routes.reports.Reports
/**
 * Created by kinshuk.bairagi on 10/12/15.
 */
class RouteRegistry(implicit mat:ActorMaterializer) extends AuthenticationDirectives {
/*
 private lazy val receptorReqHandler = new Registration().register
 private lazy val unicastHandler = new Unicast().unicast
 private lazy val callbackHandler = new Callback().callback
 private lazy val reportsRoute = new Reports().route
 private lazy val fetchRoute = new Fetch().fetch
 private lazy val stencilRoute = new StencilsRoute().stencils
 private lazy val ldapRoute = new LdapAuthentication().token
*/

 def allRoutes = authenticate {
  implicit user => {
   new Unicast().unicast ~ new Registration().register ~ new Callback().callback ~ new Reports().route ~ new Fetch().fetch ~ new StencilsRoute().stencils ~ new LdapAuthentication().token
  }
 }

}
