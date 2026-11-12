package io.github.eschizoid.telescope.demo.spring;

/** A partner's order that could not be placed, with the trace of how its payload was read. */
final class ImportRefusedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String trace;

  ImportRefusedException(final RuntimeException refused, final String trace) {
    super(refused.getMessage(), refused);
    this.trace = trace;
  }

  String trace() {
    return trace;
  }
}
