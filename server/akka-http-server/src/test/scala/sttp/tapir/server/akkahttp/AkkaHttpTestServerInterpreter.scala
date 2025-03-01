package sttp.tapir.server.akkahttp

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives.concat
import akka.http.scaladsl.server.Route
import cats.data.NonEmptyList
import cats.effect.{IO, Resource}
import sttp.capabilities.WebSockets
import sttp.capabilities.akka.AkkaStreams
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.interceptor.decodefailure.{DecodeFailureHandler, DefaultDecodeFailureHandler}
import sttp.tapir.server.interceptor.metrics.MetricsRequestInterceptor
import sttp.tapir.server.tests.TestServerInterpreter
import sttp.tapir.tests.Port

import scala.concurrent.Future

class AkkaHttpTestServerInterpreter(implicit actorSystem: ActorSystem)
    extends TestServerInterpreter[Future, AkkaStreams with WebSockets, Route] {
  override def route(
      e: ServerEndpoint[AkkaStreams with WebSockets, Future],
      decodeFailureHandler: Option[DecodeFailureHandler] = None,
      metricsInterceptor: Option[MetricsRequestInterceptor[Future]] = None
  ): Route = {
    val serverOptions: AkkaHttpServerOptions = AkkaHttpServerOptions.customInterceptors
      .metricsInterceptor(metricsInterceptor)
      .decodeFailureHandler(decodeFailureHandler.getOrElse(DefaultDecodeFailureHandler.default))
      .options
    AkkaHttpServerInterpreter(serverOptions).toRoute(e)
  }

  override def route(es: List[ServerEndpoint[AkkaStreams with WebSockets, Future]]): Route =
    AkkaHttpServerInterpreter().toRoute(es)

  override def server(routes: NonEmptyList[Route]): Resource[IO, Port] = {
    val bind = IO.fromFuture(IO(Http().newServerAt("localhost", 0).bind(concat(routes.toList: _*))))
    Resource.make(bind)(binding => IO.fromFuture(IO(binding.unbind())).void).map(_.localAddress.getPort)
  }
}
