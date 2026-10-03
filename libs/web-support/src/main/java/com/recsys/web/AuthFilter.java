package com.recsys.web;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates /v1/** requests. Modes ({@code recs.security.mode}):
 *
 * <ul>
 *   <li>{@code api-key}: {@code X-Api-Key} → service principal (local/dev).
 *   <li>{@code jwt}: {@code Authorization: Bearer} RS256/ES256 tokens verified against the
 *       gateway/IdP JWKS (issuer, audience, expiry with clock skew); {@code sub} is the user.
 *   <li>{@code jwt-or-api-key}: JWT for end users, API keys for internal services (production).
 * </ul>
 *
 * Authorization (user may only act for themselves; scopes) is enforced in controllers via {@link
 * Principals}. Actuator endpoints stay open for probes and scraping.
 */
public class AuthFilter extends OncePerRequestFilter {
  public static final String API_KEY_HEADER = "X-Api-Key";

  private final String mode;
  private final List<byte[]> keys;
  private final ConfigurableJWTProcessor<SecurityContext> jwt;

  public AuthFilter(String mode, List<String> apiKeys, SecuritySettings.Jwt jwtSettings) {
    this.mode = mode == null ? "api-key" : mode;
    this.keys = apiKeys.stream().map(k -> k.getBytes(StandardCharsets.UTF_8)).toList();
    this.jwt = this.mode.startsWith("jwt") ? processor(jwtSettings) : null;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    if (!request.getRequestURI().startsWith("/v1/")) {
      return true;
    }
    return mode.equals("api-key") && keys.isEmpty(); // auth disabled (tests / bare local runs)
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    RequestPrincipal principal = authenticate(request);
    if (principal == null) {
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setContentType("application/json");
      response
          .getWriter()
          .write("{\"error\":\"UNAUTHORIZED\",\"message\":\"missing or invalid credentials\"}");
      return;
    }
    request.setAttribute(RequestPrincipal.ATTRIBUTE, principal);
    chain.doFilter(request, response);
  }

  RequestPrincipal authenticate(HttpServletRequest request) {
    String header = request.getHeader("Authorization");
    if (jwt != null && header != null && header.startsWith("Bearer ")) {
      try {
        JWTClaimsSet claims = jwt.process(header.substring(7).strip(), null);
        Set<String> scopes = new HashSet<>();
        Object scope = claims.getClaim("scope");
        if (scope instanceof String s) {
          scopes.addAll(List.of(s.split(" ")));
        }
        return new RequestPrincipal(
            claims.getSubject(), scopes.contains("service"), Set.copyOf(scopes));
      } catch (Exception e) {
        return null; // invalid signature, expired, wrong issuer/audience, ...
      }
    }
    if (!mode.equals("jwt")) {
      String presented = request.getHeader(API_KEY_HEADER);
      if (presented != null && matches(presented.getBytes(StandardCharsets.UTF_8))) {
        return RequestPrincipal.service("api-key");
      }
    }
    return null;
  }

  private boolean matches(byte[] presented) {
    boolean ok = false;
    for (byte[] k : keys) {
      ok |= MessageDigest.isEqual(k, presented); // constant time per key
    }
    return ok;
  }

  static ConfigurableJWTProcessor<SecurityContext> processor(SecuritySettings.Jwt s) {
    if (s == null || s.jwksUri() == null || s.jwksUri().isBlank()) {
      throw new IllegalStateException("recs.security.jwt.jwks-uri is required in jwt modes");
    }
    JWKSource<SecurityContext> source;
    try {
      if (s.jwksUri().startsWith("file:")) {
        source =
            new ImmutableJWKSet<>(JWKSet.parse(Files.readString(Path.of(URI.create(s.jwksUri())))));
      } else {
        // Cached, rate-limited, refreshes on unknown kid (key rotation).
        source = JWKSourceBuilder.create(URI.create(s.jwksUri()).toURL()).retrying(true).build();
      }
    } catch (Exception e) {
      throw new IllegalStateException("cannot load JWKS from " + s.jwksUri(), e);
    }
    var p = new DefaultJWTProcessor<SecurityContext>();
    p.setJWSKeySelector(
        new JWSVerificationKeySelector<>(Set.of(JWSAlgorithm.RS256, JWSAlgorithm.ES256), source));
    var verifier =
        new DefaultJWTClaimsVerifier<SecurityContext>(
            s.audience(),
            new JWTClaimsSet.Builder().issuer(s.issuer()).build(),
            Set.of("sub", "exp", "iat"));
    verifier.setMaxClockSkew((int) s.clockSkew().toSeconds());
    p.setJWTClaimsSetVerifier(verifier);
    return p;
  }
}
