package zio.metrics
import io.prometheus.{ client => jp }
import zio.*

import java.io.StringWriter
import java.{ util => ju }
import scala.annotation.nowarn

package object prometheus2 {
  type Registry = Registry.Service

  object Registry {
    trait Service {
      def collectorRegistry: UIO[jp.CollectorRegistry]
      def updateRegistry[A](f: jp.CollectorRegistry => Task[A]): Task[A]
      def collect: UIO[ju.Enumeration[jp.Collector.MetricFamilySamples]]
      def string004: UIO[String] = collect flatMap { sampled =>
        ZIO.succeed {
          val writer = new StringWriter
          jp.exporter.common.TextFormat.write004(writer, sampled)
          writer.toString
        }
      }
    }

    private final class ServiceImpl(registry: jp.CollectorRegistry, lock: Semaphore) extends Service {

      def collectorRegistry: UIO[jp.CollectorRegistry] = ZIO.succeed(registry)

      def updateRegistry[A](f: jp.CollectorRegistry => Task[A]): Task[A] = lock.withPermit {
        f(registry)
      }

      def collect: zio.UIO[ju.Enumeration[jp.Collector.MetricFamilySamples]] =
        ZIO.succeed(registry.metricFamilySamples())
    }
    private object ServiceImpl {
      def makeWith(registry: jp.CollectorRegistry): UIO[ServiceImpl] =
        Semaphore
          .make(permits = 1)
          .map(new ServiceImpl(registry, _))
    }

    def live: ULayer[Registry] = ServiceImpl.makeWith(new jp.CollectorRegistry()).toLayer

    def default: ULayer[Registry] = ServiceImpl.makeWith(jp.CollectorRegistry.defaultRegistry).toLayer

    @nowarn
    def provided: URLayer[jp.CollectorRegistry, Registry] =
      ZIO.serviceWithZIO(ServiceImpl.makeWith).toLayer

    def defaultMetrics: RLayer[Registry, Registry] =
      ZIO
        .serviceWithZIO[Registry] { registry =>
          registry
            .updateRegistry(r => ZIO.attempt(jp.hotspot.DefaultExports.register(r)))
            .as(registry)
        }
        .toLayer

    def liveWithDefaultMetrics: TaskLayer[Registry] = live >>> defaultMetrics
  }

  def collectorRegistry: RIO[Registry, jp.CollectorRegistry] =
    ZIO.serviceWithZIO(_.collectorRegistry)
  def updateRegistry[A](f: jp.CollectorRegistry => Task[A]): RIO[Registry, A] =
    ZIO.serviceWithZIO(_.updateRegistry(f))
  def collect: RIO[Registry, ju.Enumeration[jp.Collector.MetricFamilySamples]] =
    ZIO.serviceWithZIO(_.collect)
  def string004: RIO[Registry, String] =
    ZIO.serviceWithZIO(_.string004)
}