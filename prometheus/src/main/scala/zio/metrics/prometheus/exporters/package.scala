package zio.metrics.prometheus

import zio.{ Layer, Task, ZIO, ZLayer }
import io.prometheus.client.CollectorRegistry
import io.prometheus.client.exporter.{ HTTPServer, PushGateway }
import io.prometheus.client.bridge.Graphite
import io.prometheus.client.exporter.common.TextFormat
import io.prometheus.client.exporter.HttpConnectionFactory
import io.prometheus.client.exporter.BasicAuthHttpConnectionFactory
import io.prometheus.client.hotspot.DefaultExports

import java.net.InetSocketAddress
import java.io.StringWriter

package object exporters {

  type Exporters = Exporters.Service

  object Exporters {
    trait Service {
      def http(r: CollectorRegistry, port: Int): Task[HTTPServer]

      def graphite(r: CollectorRegistry, host: String, port: Int, intervalSeconds: Int): Task[Thread]

      def pushGateway(
        r: CollectorRegistry,
        hots: String,
        port: Int,
        jobName: String,
        user: Option[String],
        password: Option[String],
        httpConnectionFactory: Option[HttpConnectionFactory]
      ): Task[Unit]

      def write004(r: CollectorRegistry): Task[String]

      def initializeDefaultExports(r: CollectorRegistry): Task[Unit]
    }

    val live: Layer[Nothing, Exporters] = ZLayer.succeed(new Service {
      def http(r: CollectorRegistry, port: Int): zio.Task[HTTPServer] =
        ZIO.succeed {
          new HTTPServer(new InetSocketAddress(port), r)
        }

      def graphite(r: CollectorRegistry, host: String, port: Int, intervalSeconds: Int): Task[Thread] =
        ZIO.succeed {
          val g = new Graphite(host, port)
          g.start(r, intervalSeconds)
        }

      def pushGateway(
        r: CollectorRegistry,
        host: String,
        port: Int,
        jobName: String,
        user: Option[String],
        password: Option[String],
        httpConnectionFactory: Option[HttpConnectionFactory]
      ): Task[Unit] =
        ZIO.succeed {
          val pg = new PushGateway(s"$host:$port")

          if (user.isDefined)
            for {
              u <- user
              p <- password
            } yield pg.setConnectionFactory(new BasicAuthHttpConnectionFactory(u, p))
          else if (httpConnectionFactory.isDefined)
            for {
              conn <- httpConnectionFactory
            } yield pg.setConnectionFactory(conn)

          pg.pushAdd(r, jobName)
        }

      def write004(r: CollectorRegistry): Task[String] =
        ZIO.succeed {
          val writer = new StringWriter
          TextFormat.write004(writer, r.metricFamilySamples)
          writer.toString
        }

      def initializeDefaultExports(r: CollectorRegistry): Task[Unit] =
        ZIO.succeed(DefaultExports.initialize())
    })

    def stopHttp(server: HTTPServer): Task[Unit] =
      ZIO.succeed(server.close())
  }

}
