package com.recsys.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Authorization checks for controllers (authentication happens in {@link AuthFilter}). */
public final class Principals {
  private Principals() {}

  public static RequestPrincipal current() {
    var attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
    if (attrs == null) {
      return RequestPrincipal.service("internal");
    }
    HttpServletRequest request = attrs.getRequest();
    Object p = request.getAttribute(RequestPrincipal.ATTRIBUTE);
    // No filter (auth disabled, e.g. local dev with no keys): treat as a trusted service.
    return p instanceof RequestPrincipal rp ? rp : RequestPrincipal.service("anonymous-dev");
  }

  /** 403 unless the caller is this user or a service principal. */
  public static void requireUser(String userId) {
    if (!current().mayActFor(userId)) {
      throw new ApiException(
          HttpStatus.FORBIDDEN, "USER_MISMATCH", "token subject does not match userId");
    }
  }

  public static void requireScope(String scope) {
    if (!current().hasScope(scope)) {
      throw new ApiException(HttpStatus.FORBIDDEN, "MISSING_SCOPE", "requires scope " + scope);
    }
  }
}
