package io.github.eschizoid.telescope.jackson.fromjson;

import io.github.eschizoid.telescope.jackson.FromJson;
import java.util.List;
import java.util.Objects;

/**
 * A bean the runtime writer builds through its constructor, whose parameters, compiled with {@code
 * -parameters}, are named after its properties in an order of their own.
 */
@FromJson
public final class MadeBean {

  private final String name;
  private final List<Integer> scores;
  private final Integer rank;

  public MadeBean(final Integer rank, final String name, final List<Integer> scores) {
    this.rank = rank;
    this.name = name;
    this.scores = scores;
  }

  public String getName() {
    return name;
  }

  public List<Integer> getScores() {
    return scores;
  }

  public Integer getRank() {
    return rank;
  }

  @Override
  public boolean equals(final Object other) {
    return (
      other instanceof MadeBean that &&
      Objects.equals(name, that.name) &&
      Objects.equals(scores, that.scores) &&
      Objects.equals(rank, that.rank)
    );
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, scores, rank);
  }

  @Override
  public String toString() {
    return "MadeBean[" + name + ", " + scores + ", " + rank + "]";
  }
}
