package com.recsys.web;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code recs.security.*}: {@code mode} = api-key | jwt | jwt-or-api-key; API keys for service
 * principals; JWT verification settings (TODO(phase-4): mTLS between services in the mesh).
 */
@ConfigurationProperties("recs.security")
public record SecuritySettings(String mode, List<String> apiKeys, Jwt jwt) {
  public SecuritySettings {
    mode = mode == null || mode.isBlank() ? "api-key" : mode;
    apiKeys = apiKeys == null ? List.of() : apiKeys.stream().filter(k -> !k.isBlank()).toList();
  }

  public record Jwt(String jwksUri, String issuer, String audience, Duration clockSkew) {
    public Jwt {
      clockSkew = clockSkew == null ? Duration.ofSeconds(60) : clockSkew;
    }
  }
}
