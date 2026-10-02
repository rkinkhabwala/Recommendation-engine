package com.recsys.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * MVP authentication: requires {@code X-Api-Key} on /v1/** (actuator stays open for probes and
 * Prometheus). TODO(phase-3): the gateway validates JWTs and asserts the user id; services verify
 * the gateway-signed identity instead of a shared key.
 */
public class ApiKeyFilter extends OncePerRequestFilter {
  public static final String HEADER = "X-Api-Key";
  private final List<byte[]> keys;

  public ApiKeyFilter(List<String> keys) {
    this.keys = keys.stream().map(k -> k.getBytes(StandardCharsets.UTF_8)).toList();
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return keys.isEmpty() || !request.getRequestURI().startsWith("/v1/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String presented = request.getHeader(HEADER);
    if (presented != null && matches(presented.getBytes(StandardCharsets.UTF_8))) {
      chain.doFilter(request, response);
      return;
    }
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setContentType("application/json");
    response
        .getWriter()
        .write("{\"error\":\"UNAUTHORIZED\",\"message\":\"missing or invalid X-Api-Key\"}");
  }

  private boolean matches(byte[] presented) {
    boolean ok = false;
    for (byte[] k : keys) {
      ok |= MessageDigest.isEqual(k, presented); // constant time per key
    }
    return ok;
  }
}
