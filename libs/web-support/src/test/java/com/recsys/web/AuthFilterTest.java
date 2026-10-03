package com.recsys.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthFilterTest {
  static RSAKey key;
  static RSAKey otherKey;
  static AuthFilter filter;

  @BeforeAll
  static void setUp(@TempDir Path dir) throws Exception {
    key = new RSAKeyGenerator(2048).keyID("k1").generate();
    otherKey = new RSAKeyGenerator(2048).keyID("k1").generate();
    Path jwks = dir.resolve("jwks.json");
    Files.writeString(jwks, new JWKSet(key.toPublicJWK()).toString());
    filter =
        new AuthFilter(
            "jwt-or-api-key",
            List.of("svc-key"),
            new SecuritySettings.Jwt(
                jwks.toUri().toString(), "https://id.example", "recsys", Duration.ofSeconds(30)));
  }

  static String token(RSAKey signer, String sub, String issuer, long expiresInSeconds, String scope)
      throws Exception {
    var claims =
        new JWTClaimsSet.Builder()
            .subject(sub)
            .issuer(issuer)
            .audience("recsys")
            .issueTime(new Date())
            .expirationTime(new Date(System.currentTimeMillis() + expiresInSeconds * 1000))
            .claim("scope", scope)
            .build();
    var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(), claims);
    jwt.sign(new RSASSASigner(signer));
    return jwt.serialize();
  }

  static MockHttpServletResponse call(String authorization, String apiKey) throws Exception {
    var req = new MockHttpServletRequest("GET", "/v1/recommendations");
    if (authorization != null) {
      req.addHeader("Authorization", authorization);
    }
    if (apiKey != null) {
      req.addHeader("X-Api-Key", apiKey);
    }
    var res = new MockHttpServletResponse();
    var chain = new MockFilterChain();
    filter.doFilter(req, res, chain);
    if (chain.getRequest() != null) {
      res.setHeader(
          "X-Principal", chain.getRequest().getAttribute(RequestPrincipal.ATTRIBUTE).toString());
    }
    return res;
  }

  @Test
  void validUserTokenAuthenticatesAsThatUser() throws Exception {
    var res = call("Bearer " + token(key, "u_42", "https://id.example", 300, "recs:read"), null);
    assertThat(res.getStatus()).isEqualTo(200);
    assertThat(res.getHeader("X-Principal")).contains("u_42").contains("service=false");
  }

  @Test
  void rejectsForgedExpiredAndWrongIssuerTokens() throws Exception {
    assertThat(
            call("Bearer " + token(otherKey, "u_42", "https://id.example", 300, ""), null)
                .getStatus())
        .isEqualTo(401);
    assertThat(
            call("Bearer " + token(key, "u_42", "https://id.example", -120, ""), null).getStatus())
        .isEqualTo(401);
    assertThat(
            call("Bearer " + token(key, "u_42", "https://evil.example", 300, ""), null).getStatus())
        .isEqualTo(401);
    assertThat(call("Bearer not-a-jwt", null).getStatus()).isEqualTo(401);
  }

  @Test
  void apiKeysAuthenticateServicesInMixedMode() throws Exception {
    assertThat(call(null, "svc-key").getHeader("X-Principal")).contains("service=true");
    assertThat(call(null, "wrong").getStatus()).isEqualTo(401);
    assertThat(call(null, null).getStatus()).isEqualTo(401);
  }

  @Test
  void usersMayOnlyActForThemselves() {
    var user = new RequestPrincipal("u_42", false, java.util.Set.of());
    assertThat(user.mayActFor("u_42")).isTrue();
    assertThat(user.mayActFor("u_7")).isFalse();
    assertThat(RequestPrincipal.service("svc").mayActFor("u_7")).isTrue();
  }
}
