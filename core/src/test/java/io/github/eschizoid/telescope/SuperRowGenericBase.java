package io.github.eschizoid.telescope;

/** An abstract base whose property {@code name} is typed by a type variable. */
public abstract class SuperRowGenericBase<T> {

  private T name;

  public T getName() {
    return name;
  }

  public void setName(final T name) {
    this.name = name;
  }
}
