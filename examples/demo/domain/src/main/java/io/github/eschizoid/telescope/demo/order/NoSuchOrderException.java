package io.github.eschizoid.telescope.demo.order;

/** No order has the number asked for. Each app answers it with a 404. */
public final class NoSuchOrderException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public NoSuchOrderException(final String number) {
    super("no order " + number);
  }
}
