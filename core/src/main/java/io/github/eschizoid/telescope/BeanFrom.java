package io.github.eschizoid.telescope;

/**
 * Intermediate of {@link Telescope#fromBean(Class)} — call {@link #to(Class)} to bind the record
 * target and continue into one of {@link BeanTo}'s {@code via*()} terminals.
 */
public final class BeanFrom<P> {

  private final Class<P> pojoClass;

  BeanFrom(final Class<P> pojoClass) {
    this.pojoClass = pojoClass;
  }

  public <R extends Record> BeanTo<P, R> to(final Class<R> recordClass) {
    return new BeanTo<>(pojoClass, recordClass);
  }
}
