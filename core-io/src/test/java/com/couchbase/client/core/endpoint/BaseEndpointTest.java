/*
 * Copyright (c) 2018 Couchbase, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.couchbase.client.core.endpoint;

import com.couchbase.client.core.CoreContext;
import com.couchbase.client.core.cnc.Event;
import com.couchbase.client.core.cnc.events.endpoint.EndpointConnectionAbortedEvent;
import com.couchbase.client.core.cnc.events.endpoint.EndpointConnectionFailedEvent;
import com.couchbase.client.core.cnc.events.endpoint.EndpointConnectedEvent;
import com.couchbase.client.core.cnc.events.endpoint.EndpointConnectionIgnoredEvent;
import com.couchbase.client.core.cnc.events.endpoint.EndpointDisconnectedEvent;
import com.couchbase.client.core.cnc.events.endpoint.EndpointDisconnectionFailedEvent;
import com.couchbase.client.core.env.CoreEnvironment;
import com.couchbase.client.core.env.IoEnvironment;
import com.couchbase.client.core.io.NetworkAddress;
import com.couchbase.client.core.msg.Request;
import com.couchbase.client.util.SimpleEventBus;
import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static com.couchbase.client.util.Utils.waitUntilCondition;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Verifies the functionality of the {@link BaseEndpoint}.
 */
class BaseEndpointTest {

  /**
   * Network address used for all the tests.
   */
  private static final NetworkAddress LOCALHOST = NetworkAddress.localhost();

  /**
   * The port used to connect.
   */
  private static final int PORT = 1234;

  private EventLoopGroup eventLoopGroup;
  private SimpleEventBus eventBus;
  private CoreEnvironment environment;
  private CoreContext ctx;

  @BeforeEach
  void beforeEach() {
    eventLoopGroup = new NioEventLoopGroup(1);
    eventBus = new SimpleEventBus();
    environment = CoreEnvironment.builder().eventBus(eventBus).build();
    ctx = new CoreContext(1, environment);
  }

  @AfterEach
  void afterEach() {
    eventLoopGroup.shutdownGracefully();
    environment.shutdown(Duration.ofSeconds(1));
  }

  /**
   * Basic sanity check that a new endpoint starts in a disconnected state.
   */
  @Test
  void startDisconnected() {
    InstrumentedEndpoint endpoint = InstrumentedEndpoint.create(eventLoopGroup, ctx, null);
    assertEquals(EndpointState.DISCONNECTED, endpoint.state());
  }

  /**
   * When connecting and the connect attempt succeeds, make sure we end up in a
   * connected state with the circuit closed and ready to go.
   */
  @Test
  void connectSuccessfully() {
    connectSuccessfully(new EmbeddedChannel());
  }

  /**
   * This test fakes a situation where the channel future from netty would simply not return
   * at all and time out, and the client would resubscribe. Then at some future attempt the
   * future returns fine and we should end up in a connected state and ready to go.
   */
  @Test
  void retryOnTimeoutUntilEventuallyConnected() {
    SimpleEventBus eventBus = new SimpleEventBus();
    CoreEnvironment env = CoreEnvironment.builder()
      .eventBus(eventBus)
      .ioEnvironment(IoEnvironment.builder()
        .connectTimeout(Duration.ofMillis(10))
        .build())
      .build();
    CoreContext ctx = new CoreContext(1, env);

    try {
      final CompletableFuture<Channel> cf = new CompletableFuture<>();
      InstrumentedEndpoint endpoint = InstrumentedEndpoint.create(
        eventLoopGroup,
        ctx,
        () -> Mono.fromFuture(cf)
      );

      endpoint.connect();
      waitUntilCondition(() -> eventBus.publishedEvents().size() >= 3);

      EmbeddedChannel channel = new EmbeddedChannel();
      cf.complete(channel);
      waitUntilCondition(() -> endpoint.state() == EndpointState.CONNECTED_CIRCUIT_CLOSED);

      assertTrue(eventBus.publishedEvents().size() >= 3);
      boolean failedFound = false;
      boolean successFound = false;
      for (Event event : eventBus.publishedEvents()) {
        if (event instanceof EndpointConnectionFailedEvent) {
          assertEquals(Event.Severity.WARN, event.severity());
          assertEquals(Duration.ofMillis(10), event.duration());
          failedFound = true;
        } else if (event instanceof EndpointConnectedEvent) {
          assertEquals(Event.Severity.DEBUG, event.severity());
          assertTrue(event.duration().toNanos() > 0);
          successFound = true;
          System.out.println(event.toString());
        }
      }
      assertTrue(failedFound);
      assertTrue(successFound);
    } finally {
      env.shutdown(Duration.ofSeconds(1));
    }
  }

  /**
   * The {@link #retryOnTimeoutUntilEventuallyConnected()} tests that a netty
   * channel future does not return at all and we reconnect, this one tests that
   * if netty returns with a failure we keep reconnecting until it succeeds.
   */
  @Test
  void retryOnFailureUntilEventuallyConnected() {
    final AtomicInteger invocationAttempt = new AtomicInteger();
    InstrumentedEndpoint endpoint = InstrumentedEndpoint.create(
      eventLoopGroup,
      ctx,
      () -> invocationAttempt.incrementAndGet() > 3
        ? Mono.just(new EmbeddedChannel())
        : Mono.error(new ChannelException("Could not connect for some reason"))
    );

    endpoint.connect();
    waitUntilCondition(() -> endpoint.state() == EndpointState.CONNECTED_CIRCUIT_CLOSED);

    assertEquals(4, eventBus.publishedEvents().size());
    int warnings = 0;
    int debug = 0;
    for (Event event : eventBus.publishedEvents()) {
      if (event.severity() == Event.Severity.WARN) {
        assertTrue(event instanceof EndpointConnectionFailedEvent);
        warnings++;
      } else if (event.severity() == Event.Severity.DEBUG) {
        assertTrue(event instanceof EndpointConnectedEvent);
        debug++;
      } else {
        throw new RuntimeException("Unexpected Event" + event);
      }
    }
    assertEquals(3, warnings);
    assertEquals(1, debug);
  }

  /**
   * This test makes sure that even if we connect successfully, if there has been a
   * disconnect signal in the meantime we need to properly close it all and not end
   * in a connect-disconnect limbo.
   */
  @Test
  void disconnectOverridesConnectCompletion() {
    final CompletableFuture<Channel> cf = new CompletableFuture<>();

    InstrumentedEndpoint endpoint = InstrumentedEndpoint.create(
      eventLoopGroup,
      ctx,
      () -> Mono.fromFuture(cf)
    );

    endpoint.connect();
    waitUntilCondition(() -> endpoint.state() == EndpointState.CONNECTING);

    endpoint.disconnect();
    EmbeddedChannel channel = new EmbeddedChannel();
    cf.complete(channel);
    waitUntilCondition(() -> endpoint.state() == EndpointState.DISCONNECTED);

    assertTrue(eventBus.publishedEvents().size() >= 2);
    assertTrue(eventBus.publishedEvents().get(0) instanceof EndpointConnectionIgnoredEvent);

    assertTrue(eventBus.publishedEvents().get(1) instanceof EndpointDisconnectedEvent);
    assertEquals(
      "Endpoint disconnected successfully",
      eventBus.publishedEvents().get(1).description()
    );
  }

  /**
   * Make sure that while we are retrying and a disconnect event comes along, we stop
   * retrying and end up in the disconnected state right away.
   */
  @Test
  void disconnectDuringRetry() {
    SimpleEventBus eventBus = new SimpleEventBus();
    CoreEnvironment env = CoreEnvironment.builder()
      .eventBus(eventBus)
      .ioEnvironment(IoEnvironment.builder()
        .connectTimeout(Duration.ofMillis(10))
        .build())
      .build();
    CoreContext ctx = new CoreContext(1, env);

    try {
      final CompletableFuture<Channel> cf = new CompletableFuture<>();
      InstrumentedEndpoint endpoint = InstrumentedEndpoint.create(
        eventLoopGroup,
        ctx,
        () -> Mono.fromFuture(cf)
      );

      endpoint.connect();
      waitUntilCondition(() -> eventBus.publishedEvents().size() >= 3);

      endpoint.disconnect();
      waitUntilCondition(() -> endpoint.state() == EndpointState.DISCONNECTED);
      waitUntilCondition(() -> eventBus.publishedEvents().size() >= 4);

      int warn = 0;
      int debug = 0;
      for (Event event : eventBus.publishedEvents()) {
        if (event.severity() == Event.Severity.WARN) {
          warn++;
          assertTrue(event instanceof EndpointConnectionFailedEvent);
        } else if (event.severity() == Event.Severity.DEBUG) {
          debug++;
          assertTrue(event instanceof EndpointConnectionAbortedEvent);
        } else {
          throw new RuntimeException("Unexpected Event: " + event);
        }
      }

      assertEquals(3, warn);
      assertEquals(1, debug);
    } finally {
      environment.shutdown(Duration.ofSeconds(1));
    }
  }

  /**
   * This test tests the happy path, after being connected properly eventually a disconnect
   * signal comes along.
   */
  @Test
  void disconnectAfterBeingConnected() {
    EmbeddedChannel channel = new EmbeddedChannel();
    InstrumentedEndpoint endpoint = connectSuccessfully(channel);
    assertTrue(channel.isOpen());
    assertTrue(channel.isActive());

    endpoint.disconnect();
    waitUntilCondition(() -> endpoint.state() == EndpointState.DISCONNECTED);
    assertFalse(channel.isOpen());
    assertFalse(channel.isActive());

    assertEquals(2, eventBus.publishedEvents().size());
    assertTrue(eventBus.publishedEvents().get(0) instanceof EndpointConnectedEvent);
    assertTrue(eventBus.publishedEvents().get(1) instanceof EndpointDisconnectedEvent);
  }

  /**
   * If the disconnect failed for some reason, make sure the proper warning event
   * is raised and captured.
   */
  @Test
  void emitsEventOnFailedDisconnect() {
    final Throwable expectedCause = new Exception("something failed");
    EmbeddedChannel channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
      @Override
      public void close(ChannelHandlerContext ctx, ChannelPromise promise) {
        promise.tryFailure(expectedCause);
      }
    });
    InstrumentedEndpoint endpoint = connectSuccessfully(channel);

    endpoint.disconnect();
    waitUntilCondition(() -> endpoint.state() == EndpointState.DISCONNECTED);

    assertEquals(2, eventBus.publishedEvents().size());
    assertTrue(eventBus.publishedEvents().get(0) instanceof EndpointConnectedEvent);
    EndpointDisconnectionFailedEvent event =
      (EndpointDisconnectionFailedEvent) eventBus.publishedEvents().get(1);
    assertEquals(expectedCause, event.cause());
    assertEquals(Event.Severity.WARN, event.severity());
  }

  /**
   * If we are fully connected and the circuit breaker is closed, make sure that we can
   * write the request into the channel and it is flushed as well.
   */
  @Test
  void writeAndFlushToChannelIfFullyConnected() {
    EmbeddedChannel channel = new EmbeddedChannel();
    InstrumentedEndpoint endpoint = connectSuccessfully(channel);

    Request<?> request = mock(Request.class);
    endpoint.send(request);
    assertEquals(request, channel.readOutbound());
  }

  /**
   * Helper method to DRY up the case where we just need to connect properly.
   *
   * @param channel the channel into which it should connect.
   * @return the connected endpoint.
   */
  private InstrumentedEndpoint connectSuccessfully(final Channel channel) {
    final CompletableFuture<Channel> cf = new CompletableFuture<>();

    InstrumentedEndpoint endpoint = InstrumentedEndpoint.create(
      eventLoopGroup,
      ctx,
      () -> Mono.fromFuture(cf)
    );

    endpoint.connect();
    waitUntilCondition(() -> endpoint.state() == EndpointState.CONNECTING);

    cf.complete(channel);
    waitUntilCondition(() -> endpoint.state() == EndpointState.CONNECTED_CIRCUIT_CLOSED);
    assertTrue(eventBus.publishedEvents().get(0) instanceof EndpointConnectedEvent);
    return endpoint;
  }

  static class InstrumentedEndpoint extends BaseEndpoint {

    private Supplier<Mono<Channel>> channelSupplier;

    static InstrumentedEndpoint create(EventLoopGroup eventLoopGroup, CoreContext ctx,
                                       Supplier<Mono<Channel>> channelSupplier) {
      return new InstrumentedEndpoint(LOCALHOST, PORT, eventLoopGroup, ctx, channelSupplier);
    }

    InstrumentedEndpoint(NetworkAddress hostname, int port, EventLoopGroup eventLoopGroup,
                         CoreContext coreContext, Supplier<Mono<Channel>> channelSupplier) {
      super(hostname, port, eventLoopGroup, coreContext);
      this.channelSupplier = channelSupplier;
    }

    @Override
    protected PipelineInitializer pipelineInitializer() {
      return pipeline -> { };
    }

    @Override
    protected Mono<Channel> channelFutureIntoMono(ChannelFuture channelFuture) {
      if (channelSupplier == null) {
        return super.channelFutureIntoMono(channelFuture);
      } else {
        return channelSupplier.get();
      }
    }
  }
}