package com.agentpay.gateway.ratelimit;

import com.agentpay.gateway.api.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.BucketProxy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-agent rate limiter at 60 req/min. Filter ordering:
 *
 * <ol>
 *   <li>Whitelist path check — actuator, .well-known, and error dispatch paths pass through.
 *   <li>Identifier resolution per endpoint (request body for /intent-tokens, JWT sub for
 *       /payments).
 *   <li>If resolution fails on a non-whitelisted path → HTTP 400.
 *   <li>Otherwise consume one token; HTTP 429 with Retry-After on exhaustion.
 * </ol>
 *
 * Runs AFTER security (so the JWT principal is available on /payments) but is exempt from auth on
 * /intent-tokens.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class RateLimitFilter extends OncePerRequestFilter {

  private static final String INTENT_TOKENS_PATH = "/intent-tokens";
  private static final String PAYMENTS_PATH = "/payments";

  private static final RequestMatcher WHITELIST =
      request ->
          PathPatternRequestMatcher.withDefaults().matcher("/actuator/**").matches(request)
              || PathPatternRequestMatcher.withDefaults()
                  .matcher("/.well-known/**")
                  .matches(request)
              || "/error".equals(request.getRequestURI());

  private final BucketRegistry buckets;
  private final ObjectMapper objectMapper;

  public RateLimitFilter(BucketRegistry buckets, ObjectMapper objectMapper) {
    this.buckets = buckets;
    this.objectMapper = objectMapper;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    if (WHITELIST.matches(request)) {
      chain.doFilter(request, response);
      return;
    }

    String path = request.getRequestURI();
    String agentId;
    HttpServletRequest forward = request;
    if (PAYMENTS_PATH.equals(path) && "POST".equalsIgnoreCase(request.getMethod())) {
      agentId = agentIdFromJwt();
    } else if (INTENT_TOKENS_PATH.equals(path) && "POST".equalsIgnoreCase(request.getMethod())) {
      byte[] raw = StreamUtils.copyToByteArray(request.getInputStream());
      forward = new ReplayableRequest(request, raw);
      agentId = agentIdFromBody(raw);
    } else {
      chain.doFilter(request, response);
      return;
    }

    if (agentId == null || agentId.isBlank()) {
      writeError(response, HttpStatus.BAD_REQUEST, "AGENT_ID_REQUIRED", "agent_id is required");
      return;
    }

    BucketProxy bucket = buckets.bucketFor(path, agentId);
    ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
    if (!probe.isConsumed()) {
      long waitNanos = probe.getNanosToWaitForRefill();
      long waitSeconds = Math.max(1, waitNanos / 1_000_000_000L);
      response.setHeader("Retry-After", Long.toString(waitSeconds));
      writeError(
          response,
          HttpStatus.TOO_MANY_REQUESTS,
          "RATE_LIMIT_EXCEEDED",
          "rate limit exceeded for agent " + agentId);
      return;
    }
    chain.doFilter(forward, response);
  }

  private static String agentIdFromJwt() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
      return null;
    }
    return jwt.getSubject();
  }

  private String agentIdFromBody(byte[] raw) {
    if (raw == null || raw.length == 0) {
      return null;
    }
    try {
      Map<?, ?> body = objectMapper.readValue(raw, Map.class);
      Object v = body.get("agent_id");
      return v == null ? null : v.toString();
    } catch (IOException e) {
      return null;
    }
  }

  private void writeError(
      HttpServletResponse response, HttpStatus status, String code, String message)
      throws IOException {
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.getWriter().write(objectMapper.writeValueAsString(new ErrorResponse(code, message)));
  }

  private static final class ReplayableRequest extends HttpServletRequestWrapper {
    private final byte[] body;

    ReplayableRequest(HttpServletRequest original, byte[] body) {
      super(original);
      this.body = body;
    }

    @Override
    public ServletInputStream getInputStream() {
      ByteArrayInputStream backing = new ByteArrayInputStream(body);
      return new ServletInputStream() {
        @Override
        public boolean isFinished() {
          return backing.available() == 0;
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {}

        @Override
        public int read() {
          return backing.read();
        }
      };
    }

    @Override
    public java.io.BufferedReader getReader() {
      return new java.io.BufferedReader(
          new java.io.InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }

    @Override
    public int getContentLength() {
      return body.length;
    }

    @Override
    public long getContentLengthLong() {
      return body.length;
    }
  }
}
