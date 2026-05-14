package com.agentpay.gateway.pii;

import com.agentpay.shared.pii.PiiRedactor;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/** Redacts PAN/IBAN from inbound JSON request bodies before any downstream handler reads them. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
public class PiiRedactionFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, jakarta.servlet.ServletException {

    String contentType = request.getContentType();
    boolean hasJsonBody =
        contentType != null
            && contentType.toLowerCase().contains("application/json")
            && request.getContentLengthLong() != 0;

    if (!hasJsonBody) {
      chain.doFilter(request, response);
      return;
    }

    byte[] raw = StreamUtils.copyToByteArray(request.getInputStream());
    byte[] redacted =
        PiiRedactor.redact(new String(raw, StandardCharsets.UTF_8))
            .getBytes(StandardCharsets.UTF_8);
    chain.doFilter(new CachedBodyRequest(request, redacted), response);
  }

  private static final class CachedBodyRequest extends HttpServletRequestWrapper {
    private final byte[] body;

    CachedBodyRequest(HttpServletRequest original, byte[] body) {
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
    public BufferedReader getReader() {
      return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
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
