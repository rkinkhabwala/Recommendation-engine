package com.recsys.openai;

/** Classified OpenAI failure so consumers know whether to retry, pause, or dead-letter. */
public class OpenAiException extends RuntimeException {

  public enum Kind {
    /** 429, 5xx, timeouts, open circuit: back off and retry later (pause consumption). */
    RETRYABLE,
    /** 400/413/422: this input will never succeed; dead-letter it. */
    BAD_INPUT,
    /** 401/403/404: operator must fix config; pause, do not dead-letter. */
    CONFIG,
    /** Monthly budget exhausted for a non-essential job. */
    BUDGET
  }

  private final Kind kind;
  private final long retryAfterMillis;

  public OpenAiException(Kind kind, String message, long retryAfterMillis, Throwable cause) {
    super(message, cause);
    this.kind = kind;
    this.retryAfterMillis = retryAfterMillis;
  }

  public OpenAiException(Kind kind, String message) {
    this(kind, message, -1, null);
  }

  public Kind kind() {
    return kind;
  }

  /** Server-provided Retry-After in ms, or -1. */
  public long retryAfterMillis() {
    return retryAfterMillis;
  }

  public boolean retryable() {
    return kind == Kind.RETRYABLE;
  }
}
