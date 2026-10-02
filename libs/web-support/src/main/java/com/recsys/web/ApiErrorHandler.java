package com.recsys.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiErrorHandler {

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ApiError> api(ApiException e) {
    return ResponseEntity.status(e.status()).body(new ApiError(e.code(), e.getMessage()));
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentNotValidException.class,
    MissingServletRequestParameterException.class,
    MethodArgumentTypeMismatchException.class
  })
  ResponseEntity<ApiError> badRequest(Exception e) {
    String msg = e.getMessage() == null ? "bad request" : e.getMessage();
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new ApiError("BAD_REQUEST", msg.length() > 300 ? msg.substring(0, 300) : msg));
  }
}
