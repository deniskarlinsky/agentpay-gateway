package com.agentpay.gateway.ratelimit;

import com.agentpay.gateway.config.RateLimitProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.codec.ByteArrayCodec;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.stereotype.Component;

/**
 * Per-agent token bucket store, Lettuce-backed. Bucket4j classic refill (60 tokens/min, capacity
 * 60). REQUIREMENTS.md says "sliding window"; the playbook explicitly permits Bucket4j as a
 * stable, approximating alternative.
 */
@Component
public class BucketRegistry {

  private final RedisClient redisClient;
  private final ProxyManager<byte[]> proxyManager;
  private final BucketConfiguration configuration;

  public BucketRegistry(RateLimitProperties props, RedisProperties redisProps) {
    RedisURI uri = RedisURI.create(redisProps.getHost(), redisProps.getPort());
    this.redisClient = RedisClient.create(uri);
    this.proxyManager =
        LettuceBasedProxyManager.builderFor(redisClient.connect(ByteArrayCodec.INSTANCE))
            .build();
    int rpm = props.requestsPerMinutePerAgent();
    this.configuration =
        BucketConfiguration.builder()
            .addLimit(Bandwidth.simple(rpm, Duration.ofMinutes(1)))
            .build();
  }

  public BucketProxy bucketFor(String endpoint, String agentId) {
    byte[] key = ("agentpay:rl:" + endpoint + ":" + agentId).getBytes(StandardCharsets.UTF_8);
    return proxyManager.builder().build(key, () -> configuration);
  }

  @PreDestroy
  void close() {
    redisClient.shutdown();
  }
}
