/*
 * Copyright 2021 ProfunKtor
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.profunktor.pulsar

import java.util.concurrent.TimeUnit

import scala.concurrent.duration.FiniteDuration

import dev.profunktor.pulsar.internal.FutureLift
import dev.profunktor.pulsar.internal.TypedMessageBuilderOps._
import dev.profunktor.pulsar.schema.Schema

import cats._
import cats.effect._
import cats.syntax.all._
import fs2.concurrent.{ Topic => _ }
import org.apache.pulsar.client.api.{ MessageId, ProducerBuilder }

trait Producer[F[_], E] {

  /**
    * Sends a message asynchronously.
    */
  def send(msg: E): F[MessageId]

  /**
    * Sends a message associated with a `key` asynchronously.
    */
  def send(msg: E, key: MessageKey): F[MessageId]

  /**
    * Same as [[send(msg:E)*]] but it discards its output.
    */
  def send_(msg: E): F[Unit]

  /**
    * Same as `send(msg:E,key:MessageKey)` but it discards its output.
    */
  def send_(msg: E, key: MessageKey): F[Unit]
}

object Producer {

  sealed trait Batching
  object Batching {
    final case class Enabled(maxDelay: FiniteDuration, maxMessages: Int) extends Batching
    case object Disabled extends Batching
  }

  /**
    * It creates a simple [[Producer]] with the supplied options.
    */
  def make[F[_]: Sync: FutureLift: Parallel, E: Schema](
      client: Pulsar.T,
      topic: Topic.Single,
      opts: Options[F, E] = null // default value does not work
  ): Resource[F, Producer[F, E]] = {
    val _opts = Option(opts).getOrElse(Options[F, E]())

    def configureBatching(
        batching: Batching,
        producerBuilder: ProducerBuilder[E]
    ): ProducerBuilder[E] =
      batching match {
        case Batching.Enabled(delay, _) =>
          producerBuilder
            .enableBatching(true)
            .batchingMaxPublishDelay(
              delay.toMillis,
              TimeUnit.MILLISECONDS
            )
            .batchingMaxMessages(5)
        case Batching.Disabled =>
          producerBuilder.enableBatching(false)
      }

    Resource
      .make {
        Sync[F].delay(
          configureBatching(
            _opts.batching,
            client.newProducer(Schema[E].schema).topic(topic.url.value)
          ).create
        )
      }(p => FutureLift[F].futureLift(p.closeAsync()).void)
      .map { prod =>
        new Producer[F, E] {
          override def send(msg: E, key: MessageKey): F[MessageId] =
            _opts.logger(msg)(topic.url) &> FutureLift[F].futureLift {
                  prod
                    .newMessage()
                    .value(msg)
                    .withShardKey(_opts.shardKey(msg))
                    .withMessageKey(key)
                    .sendAsync()
                }

          override def send_(msg: E, key: MessageKey): F[Unit] = send(msg, key).void

          override def send(msg: E): F[MessageId] = send(msg, MessageKey.Empty)

          override def send_(msg: E): F[Unit] = send(msg, MessageKey.Empty).void
        }
      }
  }

  // Builder-style abstract class instead of case class to allow for bincompat-friendly extension in future versions.
  sealed abstract class Options[F[_], E] {
    val batching: Batching
    val shardKey: E => ShardKey
    val logger: E => Topic.URL => F[Unit]
    def withBatching(_batching: Batching): Options[F, E]
    def withShardKey(_shardKey: E => ShardKey): Options[F, E]
    def withLogger(_logger: E => Topic.URL => F[Unit]): Options[F, E]
  }

  /**
    * Producer options such as sharding key, batching, and message logger
    */
  object Options {
    private case class OptionsImpl[F[_], E](
        batching: Batching,
        shardKey: E => ShardKey,
        logger: E => Topic.URL => F[Unit]
    ) extends Options[F, E] {
      override def withBatching(_batching: Batching): Options[F, E] =
        copy(batching = _batching)
      override def withShardKey(_shardKey: E => ShardKey): Options[F, E] =
        copy(shardKey = _shardKey)
      override def withLogger(_logger: E => (Topic.URL => F[Unit])): Options[F, E] =
        copy(logger = _logger)
    }
    def apply[F[_]: Applicative, E](): Options[F, E] =
      OptionsImpl[F, E](
        Batching.Disabled,
        _ => ShardKey.Default,
        _ => _ => Applicative[F].unit
      )
  }

}