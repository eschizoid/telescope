package io.github.eschizoid.telescope.benchmarks;

/**
 * A superclass whose key is fixed to {@code String} by every subclass and whose three values are
 * typed by a variable a subclass passes through or fixes.
 */
public class BenchFixedBase<K, V> {

  private K key;
  private V first;
  private V second;
  private V third;

  public K getKey() {
    return key;
  }

  public void setKey(final K key) {
    this.key = key;
  }

  public V getFirst() {
    return first;
  }

  public void setFirst(final V first) {
    this.first = first;
  }

  public V getSecond() {
    return second;
  }

  public void setSecond(final V second) {
    this.second = second;
  }

  public V getThird() {
    return third;
  }

  public void setThird(final V third) {
    this.third = third;
  }
}
