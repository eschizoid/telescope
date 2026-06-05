package io.github.eschizoid.telescope;

import java.util.function.Function;

/**
 * A single edit packaged for {@link Telescope#all(Edit[])}: a pre-built path plus the per-leaf
 * transformation to apply at that path. Build with the static {@link #over(Telescope, Function)}
 * factory (intended to be static-imported so the call site reads like a sentence).
 *
 * <pre>{@code
 * import static io.github.eschizoid.telescope.Edit.over;
 *
 * static final Telescope<Company, String> EMAILS     = ...;
 * static final Telescope<Company, String> DEPT_NAMES = ...;
 *
 * final Telescope<Company, Company> normalize = Telescope.all(
 *     over(EMAILS,     String::toLowerCase),
 *     over(DEPT_NAMES, String::trim));
 *
 * final Company a = normalize.apply(companyA);
 * normalize.apply(companyB);    // reusable across sources
 * }</pre>
 *
 * <p>The leaf type {@code X} is captured per-edit and hidden once the edit is boxed into {@code
 * Edit<S>}, so {@code Telescope.all(Edit<S>...)} can take heterogeneous edits (each with its own
 * leaf type) without users having to name them.
 *
 * <p>Compared to the existing chain accumulator ({@link Telescope#update(Telescope, Function)} /
 * {@link Telescope#with(Function)}), {@code Telescope.all(over(...), over(...))} is the recommended
 * shape for two or more distinct paths: each edit lives on its own line, the count is visible at a
 * glance, and there is no visual chain-blur between paths.
 */
public interface Edit<S> {
  /**
   * Bind a pre-built telescope to its per-leaf transformation. Pass the result to {@link
   * Telescope#all(Edit[])}.
   *
   * @param path the navigation to follow when this edit runs
   * @param fn the transformation at the focused leaf
   * @param <S> the root type the path starts at
   * @param <X> the leaf type the path focuses on (existential — hidden once returned as {@code
   *     Edit<S>})
   */
  static <S, X> Edit<S> over(final Telescope<S, X> path, final Function<X, X> fn) {
    return new EditImpl<>(path, fn);
  }

  /**
   * Run this edit against {@code s}. Called by {@link Telescope#all(Edit[])} when it folds the
   * edits into a single {@code Function<S, S>}.
   */
  S apply(S s);
}

/**
 * The single {@link Edit} implementation. Package-private so users construct edits only through
 * {@link Edit#over(Telescope, Function)} and never see this type at the call site.
 */
record EditImpl<S, X>(Telescope<S, X> path, Function<X, X> fn) implements Edit<S> {
  @Override
  public S apply(final S s) {
    return path.update(s, fn);
  }
}
