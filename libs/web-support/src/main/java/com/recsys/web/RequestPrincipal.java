package com.recsys.web;

import java.util.Set;

/**
 * Who is calling. End users (JWT {@code sub}) may only act for themselves; service principals (API
 * key, or JWT with the {@code service} scope) act for any user — e.g. the gateway's BFF, catalog
 * loaders, the enrichment worker.
 */
public record RequestPrincipal(String subject, boolean service, Set<String> scopes) {
  public static final String ATTRIBUTE = "recs.principal";

  public static RequestPrincipal service(String name) {
    return new RequestPrincipal(name, true, Set.of("service", "catalog:write"));
  }

  public boolean hasScope(String scope) {
    return scopes.contains(scope);
  }

  public boolean mayActFor(String userId) {
    return service || subject.equals(userId);
  }
}
