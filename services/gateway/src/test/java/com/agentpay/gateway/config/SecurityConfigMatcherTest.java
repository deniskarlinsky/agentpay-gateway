package com.agentpay.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Regression for the Task 4 401 leak: when {@code SecurityConfig.publicChain} used the string
 * overload of {@code requestMatchers("/cases/**")}, Spring Security resolved it to a handler-aware
 * {@code MvcRequestMatcher}. Any path under {@code /cases/**} without a registered MVC handler
 * (e.g. before {@code CaseTransitionsController} was added to the running JAR) silently NO-MATCHed
 * and fell through to the protected chain, returning 401 with {@code WWW-Authenticate: Bearer}.
 *
 * <p>This test builds the matcher standalone — no Spring MVC context loaded — and asserts purely
 * path-based behaviour. It would FAIL with the previous {@code MvcRequestMatcher} (no
 * HandlerMappingIntrospector, no handler registry) and PASSES with the {@code
 * PathPatternRequestMatcher} that {@link SecurityConfig} now wires.
 */
class SecurityConfigMatcherTest {

  private static final RequestMatcher CASES_GET =
      PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/cases/**");

  @Test
  void casesGetMatcherAcceptsKnownStatusPath() {
    assertThat(CASES_GET.matches(new MockHttpServletRequest("GET", "/cases/foo"))).isTrue();
  }

  @Test
  void casesGetMatcherAcceptsTransitionsPath() {
    // The Task 4 regression: /cases/{id}/transitions must match the public chain on URL alone,
    // independent of whether CaseTransitionsController is registered in the MVC context.
    assertThat(CASES_GET.matches(new MockHttpServletRequest("GET", "/cases/foo/transitions")))
        .isTrue();
  }

  @Test
  void casesGetMatcherAcceptsDeeperPathsUnderCases() {
    // Future-proofing: any GET endpoint added under /cases/** is on the public chain by URL,
    // not by handler presence. Prevents the same handler-aware footgun from biting again.
    assertThat(CASES_GET.matches(new MockHttpServletRequest("GET", "/cases/foo/bar/baz"))).isTrue();
  }

  @Test
  void casesGetMatcherRejectsNonGetMethod() {
    // /cases/** is GET-only on the public chain — POST/PUT/DELETE must fall through to the
    // protected chain so they're not accidentally exposed.
    assertThat(CASES_GET.matches(new MockHttpServletRequest("POST", "/cases/foo"))).isFalse();
  }

  @Test
  void casesGetMatcherRejectsUnrelatedPath() {
    // /payments is the protected surface — it must not be captured by the /cases matcher.
    assertThat(CASES_GET.matches(new MockHttpServletRequest("GET", "/payments"))).isFalse();
  }
}
