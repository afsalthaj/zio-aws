package io.github.vigoo.zioaws.core

import java.net.URI

import io.github.vigoo.zioaws.core.httpclient.HttpClient
import software.amazon.awssdk.auth.credentials._
import software.amazon.awssdk.awscore.client.builder.{ AwsAsyncClientBuilder, AwsClientBuilder }
import software.amazon.awssdk.awscore.retry.conditions.RetryOnErrorCodeCondition
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.retry.backoff.{
  BackoffStrategy,
  EqualJitterBackoffStrategy,
  FixedDelayBackoffStrategy,
  FullJitterBackoffStrategy
}
import software.amazon.awssdk.core.retry.conditions._
import software.amazon.awssdk.core.retry.{ RetryMode, RetryPolicy, RetryPolicyContext }
import software.amazon.awssdk.regions.Region
import zio.config.ConfigDescriptor._
import zio.config._
import zio.duration._
import zio.{ Has, Task, ZIO, ZLayer }

import scala.concurrent.duration.Duration
import scala.jdk.CollectionConverters._
import scala.util.{ Failure, Success, Try }
import io.github.vigoo.zioaws.core.config.descriptors.CredentialsProvider.Default
import io.github.vigoo.zioaws.core.config.descriptors.CredentialsProvider.Anonymous
import io.github.vigoo.zioaws.core.config.descriptors.CredentialsProvider.StaticProvider
import io.github.vigoo.zioaws.core.config.descriptors.AwsRetryPolicy.Default
import io.github.vigoo.zioaws.core.config.descriptors.AwsRetryPolicy.Legacy
import io.github.vigoo.zioaws.core.config.descriptors.AwsRetryPolicy.NoPolicy
import io.github.vigoo.zioaws.core.config.descriptors.AwsRetryPolicy.Standard
import io.github.vigoo.zioaws.core.config.descriptors.AwsRetryMode.Legacy
import io.github.vigoo.zioaws.core.config.descriptors.AwsRetryMode.Standard
import io.github.vigoo.zioaws.core.config.descriptors.AwsRetryMode.Default

package object config {
  type AwsConfig = Has[AwsConfig.Service]

  object AwsConfig {

    trait Service {
      def configure[Client, Builder <: AwsClientBuilder[Builder, Client]](
        builder: Builder
      ): Task[Builder]

      def configureHttpClient[
        Client,
        Builder <: AwsAsyncClientBuilder[Builder, Client]
      ](builder: Builder): Task[Builder]
    }

  }

  val default: ZLayer[HttpClient, Nothing, AwsConfig] = customized(
    ClientCustomization.None
  )

  trait ClientCustomization {
    def customize[Client, Builder <: AwsClientBuilder[Builder, Client]](
      builder: Builder
    ): Builder
  }

  object ClientCustomization {

    object None extends ClientCustomization {
      override def customize[Client, Builder <: AwsClientBuilder[
        Builder,
        Client
      ]](builder: Builder): Builder = builder
    }

  }

  def customized(
    customization: ClientCustomization
  ): ZLayer[HttpClient, Nothing, AwsConfig] =
    ZLayer.fromService { httpClient =>
      new AwsConfig.Service {
        override def configure[Client, Builder <: AwsClientBuilder[
          Builder,
          Client
        ]](builder: Builder): Task[Builder] =
          Task(customization.customize[Client, Builder](builder))

        override def configureHttpClient[
          Client,
          Builder <: AwsAsyncClientBuilder[Builder, Client]
        ](builder: Builder): Task[Builder] =
          ZIO.succeed(builder.httpClient(httpClient.client))
      }
    }

  case class CommonClientConfig(
    extraHeaders: Map[String, List[String]],
    retryPolicy: Option[RetryPolicy],
    apiCallTimeout: Option[Duration],
    apiCallAttemptTimeout: Option[Duration],
    defaultProfileName: Option[String]
  )

  case class CommonAwsConfig(
    region: Option[Region],
    credentialsProvider: AwsCredentialsProvider,
    endpointOverride: Option[URI],
    commonClientConfig: Option[CommonClientConfig]
  )

  def configured(): ZLayer[HttpClient with ZConfig[CommonAwsConfig], Nothing, AwsConfig] =
    ZLayer
      .fromServices[HttpClient.Service, CommonAwsConfig, AwsConfig.Service] { (httpClient, commonConfig) =>
        new AwsConfig.Service {
          override def configure[Client, Builder <: AwsClientBuilder[
            Builder,
            Client
          ]](builder: Builder): Task[Builder] = {
            val builderHelper: BuilderHelper[Client] = BuilderHelper.apply
            import builderHelper._
            Task {
              val b0 =
                builder
                  .optionallyWith(commonConfig.endpointOverride)(
                    _.endpointOverride
                  )
                  .optionallyWith(commonConfig.region)(_.region)
                  .credentialsProvider(commonConfig.credentialsProvider)

              commonConfig.commonClientConfig match {
                case Some(commonClientConfig) =>
                  val clientOverrideBuilderHelper: BuilderHelper[ClientOverrideConfiguration] =
                    BuilderHelper.apply
                  import clientOverrideBuilderHelper._
                  val overrideBuilder =
                    ClientOverrideConfiguration
                      .builder()
                      .headers(
                        commonClientConfig.extraHeaders.map { case (key, value) => key -> value.asJava }.toMap.asJava
                      )
                      .optionallyWith(commonClientConfig.retryPolicy)(
                        _.retryPolicy
                      )
                      .optionallyWith(
                        commonClientConfig.apiCallTimeout
                          .map(zio.duration.Duration.fromScala)
                      )(_.apiCallTimeout)
                      .optionallyWith(
                        commonClientConfig.apiCallAttemptTimeout
                          .map(zio.duration.Duration.fromScala)
                      )(_.apiCallAttemptTimeout)
                      .optionallyWith(commonClientConfig.defaultProfileName)(
                        _.defaultProfileName
                      )

                  b0.overrideConfiguration(overrideBuilder.build())
                case None =>
                  b0
              }
            }
          }

          override def configureHttpClient[
            Client,
            Builder <: AwsAsyncClientBuilder[Builder, Client]
          ](builder: Builder): Task[Builder] =
            ZIO.succeed(builder.httpClient(httpClient.client))
        }
      }

  object descriptors {
    val region: ConfigDescriptor[Region] = string.xmap(Region.of, _.id())

    val awsCredentials: ConfigDescriptor[AwsCredentials] =
      (string("accessKeyId") ?? "AWS access key ID" |@|
        string("secretAccessKey") ?? "AWS secret access key")(
        AwsBasicCredentials.create,
        (creds: AwsCredentials) => Some((creds.accessKeyId(), creds.secretAccessKey()))
      )

    sealed trait CredentialsProvider {
      def toAwsCredentialsProvider: Task[AwsCredentialsProvider] =
        this match {
          case Default   => Task(DefaultCredentialsProvider.create())
          case Anonymous => Task(AnonymousCredentialsProvider.create())
          case StaticProvider(accessKeyId, secretAccessKey) =>
            Task(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey)))

        }
    }

    import zio.config.magnolia._, zio.config.magnolia.DeriveConfigDescriptor.descriptor

    object CredentialsProvider {
      @name("default")
      case object Default extends CredentialsProvider

      @name("anonymous")
      case object Anonymous extends CredentialsProvider
      case class StaticProvider(
        @describe("AWS access key ID") accessKeyId: String,
        @describe("AWS secret access key") secretAccessKey: String
      ) extends CredentialsProvider
    }

    val credentialsProvider: ConfigDescriptor[CredentialsProvider] =
      descriptor[CredentialsProvider]

    val rawHeader: ConfigDescriptor[(String, List[String])] =
      (string("name") ?? "Header name" |@|
        listOrSingleton("value")(string ?? "Header value")).tupled

    val rawHeaderMap: ConfigDescriptor[Map[String, List[String]]] =
      list(rawHeader).xmap(
        _.toMap,
        _.toList
      )

    sealed trait AwsRetryPolicy {
      def toAwsRetryPolicy: Task[RetryPolicy] = this match {
        case Default  => Task(RetryPolicy.defaultRetryPolicy())
        case Legacy   => Task(RetryPolicy.forRetryMode(RetryMode.LEGACY))
        case NoPolicy => Task(RetryPolicy.forRetryMode(RetryMode.STANDARD))
        case Standard => Task(RetryPolicy.none())
      }
    }

    object AwsRetryPolicy {
      @name("default")
      case object Default extends AwsRetryPolicy

      @name("legacy")
      case object Legacy extends AwsRetryPolicy

      @name("standard")
      case object Standard extends AwsRetryPolicy

      @name("none")
      case object NoPolicy extends AwsRetryPolicy
    }

    sealed trait AwsRetryMode {
      def toAwsRetryMode: RetryMode = this match {
        case Legacy   => RetryMode.LEGACY
        case Standard => RetryMode.STANDARD
        case Default  => RetryMode.defaultRetryMode()
      }
    }

    object AwsRetryMode {
      @name("legacy")
      case object Legacy extends AwsRetryMode

      @name("standard")
      case object Standard extends AwsRetryMode

      @name("default")
      case object Default extends AwsRetryMode
    }

    val retryPolicy: ConfigDescriptor[RetryPolicy] = {
      val backoffStrategy: ConfigDescriptor[BackoffStrategy] = {
        val fullJitter: ConfigDescriptor[BackoffStrategy] =
          nested("fullJitter")(
            (duration("baseDelay") |@| duration("maxBackoffTime"))(
              (baseDelay, maxBackoffTime) =>
                FullJitterBackoffStrategy
                  .builder()
                  .baseDelay(zio.duration.Duration.fromScala(baseDelay))
                  .maxBackoffTime(
                    zio.duration.Duration.fromScala(maxBackoffTime)
                  )
                  .build(), {
                case bs: FullJitterBackoffStrategy =>
                  Some(
                    (
                      bs.toBuilder.baseDelay.asScala,
                      bs.toBuilder.maxBackoffTime.asScala
                    )
                  )
                case _ => None
              }
            )
          )
        val equalJitter: ConfigDescriptor[BackoffStrategy] =
          nested("equalJitter")(
            (duration("baseDelay") |@| duration("maxBackoffTime"))(
              (baseDelay, maxBackoffTime) =>
                EqualJitterBackoffStrategy
                  .builder()
                  .baseDelay(zio.duration.Duration.fromScala(baseDelay))
                  .maxBackoffTime(
                    zio.duration.Duration.fromScala(maxBackoffTime)
                  )
                  .build(), {
                case bs: EqualJitterBackoffStrategy =>
                  Some(
                    (
                      bs.toBuilder.baseDelay.asScala,
                      bs.toBuilder.maxBackoffTime.asScala
                    )
                  )
                case _ => None
              }
            )
          )

        val fixed: ConfigDescriptor[BackoffStrategy] =
          nested("fixed")(
            duration("backoff")(
              backoff =>
                FixedDelayBackoffStrategy
                  .create(zio.duration.Duration.fromScala(backoff)), {
                case bs: FixedDelayBackoffStrategy =>
                  Some(
                    bs.computeDelayBeforeNextRetry(
                        RetryPolicyContext.builder().build()
                      )
                      .asScala
                  )
                case _ => None
              }
            )
          )
        fullJitter <> equalJitter <> fixed
      }

      sealed trait AwsRetryCondition {
        def toRetryCondition: RetryCondition = this match {
          case AwsRetryCondition.Default              => RetryCondition.defaultRetryCondition()
          case AwsRetryCondition.NoRetry              => RetryCondition.defaultRetryCondition()
          case AwsRetryCondition.Or(lst)              => OrRetryCondition.create(lst: _*)
          case AwsRetryCondition.And(lst)             => AndRetryCondition.create(lst: _*)
          case AwsRetryCondition.MaxRetryCondition(n) => MaxNumberOfRetriesCondition.create(n)
          case AwsRetryCondition.RetryOn(strategy) =>
            strategy match {
              case ClockSkew  => RetryOnClockSkewCondition.create()
              case Throttling => RetryOnThrottlingCondition.create()
            }
        }
      }

      object AwsRetryCondition {
        val config = descriptor[AwsRetryCondition].desc

        @name("default")
        case object Default extends AwsRetryCondition

        @name("none")
        case object NoRetry extends AwsRetryCondition

        @name("or")
        case class Or(or: List[AwsRetryCondition]) extends AwsRetryCondition

        object Or {
          import zio.config.magnolia.DeriveConfigDescriptor.Descriptor
          implicit val orRetryCondition: Descriptor[Or] =
            Descriptor(list(AwsRetryCondition.config))(Or.apply, Or.unapply)
        }

        @name("and")
        case class And(or: List[AwsRetryCondition]) extends AwsRetryCondition

        object And {
          import zio.config.magnolia.DeriveConfigDescriptor.Descriptor
          implicit val andRetryCondition: Descriptor[And] =
            Descriptor(list(AwsRetryCondition.config))(And.apply, And.unapply)
        }
        @name("maxNumberOfRetries")
        case class MaxRetryCondition(n: Int) extends AwsRetryCondition

        object MaxRetryCondition {
          implicit val max: Descriptor[MaxRetryCondition] =
            Descriptor(int.xmap(MaxRetryCondition.apply, AMaxRetryCondition.unapply))
        }

        @name("retryOn")
        case class RetryOn(strategy: RetryOn.AwsRetryOnStrategy) extends AwsRetryCondition

        object RetryOn {
          sealed trait AwsRetryOnStrategy

          object AwsRetryOn {
            @name("clockSkew")
            case object ClockSkew extends AwsRetryOnStrategy
            @name("throttling")
            case object Throttling extends AwsRetryOnStrategy
          }
        }
      }

      def retryCondition: ConfigDescriptor[RetryCondition] = {
        val retryOnErrorCodes: ConfigDescriptor[RetryCondition] =
          (listOrSingleton("retryOnErrorCode")(string))(
            codes => RetryOnErrorCodeCondition.create(codes.toSet.asJava),
            _ => None // NOTE: Cannot extract conditions without reflection
          )
        val retryOnExceptions: ConfigDescriptor[RetryCondition] =
          (listOrSingleton("retryOnException")(string)).xmapEither(
            lst =>
              lst
                .foldLeft[Either[String, List[Class[Exception]]]](
                  Left("empty list")
                ) {
                  case (Left(msg), _) => Left(msg)
                  case (Right(result), name) =>
                    Try(
                      Class.forName(name).asInstanceOf[Class[Exception]]
                    ) match {
                      case Failure(exception) => Left(exception.toString)
                      case Success(value)     => Right(value :: result)
                    }
                }
                .map(classes => RetryOnExceptionsCondition.create(classes: _*)),
            _ =>
              Left(
                "cannot extract RetryOnExceptionsCondition"
              ) // NOTE: Cannot extract conditions without reflection
          )
        val retryOnStatusCodes: ConfigDescriptor[RetryCondition] =
          (listOrSingleton("retryOnStatusCode")(int))(
            codes =>
              RetryOnStatusCodeCondition.create(
                codes.map(int2Integer).toSet.asJava
              ),
            _ => None // NOTE: Cannot extract conditions without reflection
          )
        val tokenBucket: ConfigDescriptor[RetryCondition] =
          nested("tokenBucket")(
            (int(
              "bucketSize"
            ) ?? "Maximum number of tokens in the token bucket" |@|
              nested("costFunction")(
                (int(
                  "throttlingExceptionCost"
                ) ?? "Number of tokens to be removed on throttling exceptions" |@|
                  int(
                    "exceptionCost"
                  ) ?? "Number of tokens to be removed on other exceptions")(
                  (throttlingCost, cost) =>
                    TokenBucketExceptionCostFunction
                      .builder()
                      .throttlingExceptionCost(throttlingCost)
                      .defaultExceptionCost(cost)
                      .build(),
                  (_: TokenBucketExceptionCostFunction) => None // NOTE: Cannot extract conditions without reflection
                )
              ))(
              (size, costFn) =>
                TokenBucketRetryCondition
                  .builder()
                  .tokenBucketSize(size)
                  .exceptionCostFunction(costFn)
                  .build(),
              _ => None // NOTE: Cannot extract conditions without reflection
            )
          )

        default <> none <> or <> and <> maxNumberOfRetries <> retryOn <> retryOnErrorCodes <> retryOnExceptions <> retryOnStatusCodes <> tokenBucket
      }

      val custom: ConfigDescriptor[RetryPolicy] =
        (nested("mode")(retryMode) ?? "Retry mode" |@|
          int("numRetries") ?? "Maximum number of retries" |@|
          boolean(
            "additionalRetryConditionsAllowed"
          ) ?? "Allows additional retry conditions" |@|
          nested("backoffStrategy")(backoffStrategy).default(
            BackoffStrategy.defaultStrategy()
          ) ?? "Backoff strategy for waiting between retries" |@|
          nested("throttlingBackoffStrategy")(backoffStrategy).default(
            BackoffStrategy.defaultThrottlingStrategy()
          ) ?? "Backoff strategy for waiting after throttling error" |@|
          nested("retryCondition")(
            retryCondition
          ) ?? "Condition deciding which request should be retried" |@|
          nested("retryCapacityCondition")(
            retryCondition
          ) ?? "Condition deciding throttling")(
          (
            mode,
            numRetries,
            additionalRetryConditionsAllowed,
            backoffStrategy,
            throttlingBackoffStrategy,
            retryCondition,
            retryCapacityCondition
          ) =>
            RetryPolicy
              .builder(mode)
              .numRetries(numRetries)
              .additionalRetryConditionsAllowed(
                additionalRetryConditionsAllowed
              )
              .backoffStrategy(backoffStrategy)
              .throttlingBackoffStrategy(throttlingBackoffStrategy)
              .retryCondition(retryCondition)
              .retryCapacityCondition(retryCapacityCondition)
              .build(),
          p =>
            Some(
              (
                p.retryMode(),
                p.numRetries(),
                p.additionalRetryConditionsAllowed(),
                p.backoffStrategy(),
                p.throttlingBackoffStrategy(),
                p.retryCondition(),
                p.toBuilder.retryCapacityCondition()
              )
            )
        )

      default <> legacy <> standard <> none <> custom
    }
    val commonClientConfig: ConfigDescriptor[CommonClientConfig] =
      (nested("extraHeaders")(
        rawHeaderMap
      ) ?? "Extra headers to be sent with each request" |@|
        nested("retryPolicy")(retryPolicy).optional ?? "Retry policy" |@|
        duration(
          "apiCallTimeout"
        ).optional ?? "Amount of time to allow the client to complete the execution of an API call" |@|
        duration(
          "apiCallAttemptTimeout"
        ).optional ?? "Amount of time to wait for the HTTP request to complete before giving up" |@|
        string("defaultProfileName").optional ?? "Default profile name")(
        CommonClientConfig.apply,
        CommonClientConfig.unapply
      )

    val commonAwsConfig: ConfigDescriptor[CommonAwsConfig] =
      (nested("region")(region).optional ?? "AWS region to connect to" |@|
        nested("credentials")(credentialsProvider).default(
          DefaultCredentialsProvider.create()
        ) ?? "AWS credentials provider" |@|
        uri(
          "endpointOverride"
        ).optional ?? "Overrides the AWS service endpoint" |@|
        nested("client")(
          commonClientConfig
        ).optional ?? "Common settings for AWS service clients")(
        CommonAwsConfig.apply,
        CommonAwsConfig.unapply
      )
  }

}

object X extends App {
  import zio.config._

  println(generateDocs(config.descriptors.commonAwsConfig).toTable.asGithubFlavouredMarkdown)
}
