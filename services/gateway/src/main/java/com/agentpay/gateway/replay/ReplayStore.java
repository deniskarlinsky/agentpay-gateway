package com.agentpay.gateway.replay;

import java.nio.charset.StandardCharsets;
import org.springframework.data.redis.connection.RedisStringCommands.SetOption;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.stereotype.Component;

/**
 * Tracks consumed intent-token jti values. Backed by Redis with `SET key value NX EX <ttl>` — the
 * atomic single-command form (Redis 2.6.12+) — so a concurrent second submission of the same token
 * cannot win the race.
 */
@Component
public class ReplayStore {

  private static final String KEY_PREFIX = "agentpay:jti:";

  private final StringRedisTemplate template;

  public ReplayStore(StringRedisTemplate template) {
    this.template = template;
  }

  /**
   * Attempts to claim the given jti for the given TTL.
   *
   * @return {@code true} if this caller claimed it (first use), {@code false} if it was already
   *     present (replay) or the TTL is non-positive.
   */
  public boolean claim(String jti, long ttlSeconds) {
    if (ttlSeconds <= 0) {
      return false;
    }
    byte[] key = (KEY_PREFIX + jti).getBytes(StandardCharsets.UTF_8);
    byte[] value = new byte[] {'1'};
    Boolean ok =
        template.execute(
            (RedisCallback<Boolean>)
                conn ->
                    conn.stringCommands()
                        .set(key, value, Expiration.seconds(ttlSeconds), SetOption.SET_IF_ABSENT));
    return Boolean.TRUE.equals(ok);
  }
}
