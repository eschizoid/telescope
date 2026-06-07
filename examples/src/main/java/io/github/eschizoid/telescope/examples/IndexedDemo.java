package io.github.eschizoid.telescope.examples;

import io.github.eschizoid.telescope.Telescope;
import java.util.List;

/**
 * Exercises the indexed-traversal terminals: {@code .updateIndexed(s, (i, a) -> ...)} stand-alone,
 * {@code .toListIndexed(s)} stand-alone, and the chainable {@code .withIndex()} view returning
 * {@code WithIndex<S, A>} with its own {@code update / toList / find / count / exists}.
 */
final class IndexedDemo {

  private IndexedDemo() {}

  record Step(String name) {}

  record Pipeline(String label, List<Step> steps) {}

  static void run() {
    final var pipeline = new Pipeline("etl", List.of(new Step("extract"), new Step("transform"), new Step("load")));

    final var steps = Telescope.of(Pipeline.class).each(Pipeline::steps).field(Step::name);

    // Terminal updateIndexed(s, (i, a) -> ...): prefix every step name with its index.
    final var numbered = steps.updateIndexed(pipeline, (i, name) -> i + "-" + name);
    System.out.println("[updateIndexed] numbered     : " + numbered);

    // Terminal toListIndexed(s): each emission carries its index alongside the value.
    System.out.println("[toListIndexed] read         : " + steps.toListIndexed(pipeline));

    // Chainable withIndex(): same data, different ergonomics — the WithIndex view holds the index.
    final var indexed = steps.withIndex();
    final var doubled = indexed.update(pipeline, (i, name) -> "[" + i + "] " + name.toUpperCase());
    System.out.println("[withIndex]     update       : " + doubled);
    System.out.println("[withIndex]     toList       : " + indexed.toList(pipeline));
    System.out.println("[withIndex]     count        : " + indexed.count(pipeline));
    System.out.println("[withIndex]     find         : " + indexed.find(pipeline));
  }
}
