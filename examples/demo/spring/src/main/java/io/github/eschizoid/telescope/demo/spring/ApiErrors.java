package io.github.eschizoid.telescope.demo.spring;

import io.github.eschizoid.telescope.demo.order.NoSuchOrderException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tools.jackson.core.JacksonException;

/** Turns the desk's refusals into problem responses. */
@RestControllerAdvice
class ApiErrors {

  @ExceptionHandler(NoSuchOrderException.class)
  ProblemDetail notFound(final NoSuchOrderException refused) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, refused.getMessage());
  }

  @ExceptionHandler(ImportRefusedException.class)
  ProblemDetail importRefused(final ImportRefusedException refused) {
    final var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, refused.getMessage());
    problem.setProperty("trace", refused.trace());
    return problem;
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ProblemDetail badRequest(final IllegalArgumentException refused) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, refused.getMessage());
  }

  /** A body that is not JSON at all, which the partner feed's binder hands back as it parses. */
  @ExceptionHandler(JacksonException.class)
  ProblemDetail notJson(final JacksonException refused) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, refused.getOriginalMessage());
  }

  @ExceptionHandler(IllegalStateException.class)
  ProblemDetail conflict(final IllegalStateException refused) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, refused.getMessage());
  }
}
