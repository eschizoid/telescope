package io.github.eschizoid.telescope.ownbridge;

/** A bean whose type variable is bounded by the bean itself. */
public class ObRank<R extends ObRank<R>> {

  private R next;

  public ObRank() {}

  public R getNext() {
    return next;
  }

  public void setNext(final R next) {
    this.next = next;
  }
}
