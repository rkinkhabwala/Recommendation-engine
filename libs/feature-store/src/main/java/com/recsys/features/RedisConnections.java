package com.recsys.features;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import java.time.Duration;

/** Builds the String-key / byte[]-value Lettuce connection used by {@link RedisFeatureStore}. */
public final class RedisConnections {
  private RedisConnections() {}

  public static StatefulRedisConnection<String, byte[]> connect(
      RedisClient client, String uri, Duration commandTimeout) {
    client.setOptions(
        ClientOptions.builder()
            .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofSeconds(2)).build())
            .timeoutOptions(TimeoutOptions.enabled(commandTimeout))
            // Fail fast while disconnected instead of buffering commands: the serving path
            // must degrade, not queue.
            .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
            .build());
    RedisURI redisUri = RedisURI.create(uri);
    return client.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE), redisUri);
  }
}
