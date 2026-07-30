package io.github.eschizoid.telescope;

import java.util.function.BiFunction;
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
    if (path.hasPendingEdits()) throw new IllegalArgumentException(
      "over(...) received a telescope carrying pending chain edits (built via .with(...) / " +
        ".update(path, fn) / Telescope.all(...)); the edit would run against the bare path and " +
        "silently drop them. Run them with .apply(source) first, or pass the pure path."
    );
    return new EditImpl<>(path, fn);
  }

  /**
   * Conditional edit — directly replace the focused leaf with {@code value} when it is non-null,
   * otherwise do nothing. The ergonomic shape for sparse-PATCH controllers where each request DTO
   * field is nullable and you want to land it 1:1 on the domain.
   *
   * <pre>{@code
   * Telescope.all(
   *     Edit.overIfPresent(ORDER_NUMBER,  req.orderNumber()),
   *     Edit.overIfPresent(SHIPPING_CITY, req.shippingCity()))
   * }</pre>
   *
   * <p>If {@code value} is {@code null}, the returned edit is identity — the surrounding {@link
   * Telescope#all(Edit[])} composition still runs, but this slot contributes no change.
   */
  static <S, X> Edit<S> overIfPresent(final Telescope<S, X> path, final X value) {
    return value == null ? identity() : new EditImpl<>(path, __ -> value);
  }

  /**
   * Conditional edit — replace the focused leaf with {@code mapper.apply(value)} when {@code value}
   * is non-null, otherwise do nothing. Use this when the leaf type differs from the DTO type (e.g.
   * lower-case the incoming email before it lands).
   *
   * <pre>{@code
   * Edit.overIfPresent(CUSTOMER_EMAIL, req.customerEmail(), String::toLowerCase)
   * }</pre>
   */
  static <S, X, V> Edit<S> overIfPresent(final Telescope<S, X> path, final V value, final Function<V, X> mapper) {
    return value == null ? identity() : new EditImpl<>(path, __ -> mapper.apply(value));
  }

  /**
   * Conditional edit — when {@code value} is non-null, transform each focused leaf via {@code
   * transform.apply(value, currentLeaf)}; otherwise do nothing. Use this when the patch carries a
   * delta or an instruction rather than a replacement.
   *
   * <pre>{@code
   * Edit.mapIfPresent(LINE_ITEM_QUANTITIES, req.quantityDelta(), (delta, q) -> q + delta)
   * }</pre>
   *
   * <p>Distinct name from {@link #overIfPresent(Telescope, Object, Function)} so the lambda's arity
   * does not need to disambiguate the overload at the call site — pick {@code overIfPresent} when
   * the value alone yields the new leaf; pick {@code mapIfPresent} when the new leaf depends on
   * both the value and the current leaf.
   */
  static <S, X, V> Edit<S> mapIfPresent(
    final Telescope<S, X> path,
    final V value,
    final BiFunction<V, X, X> transform
  ) {
    return value == null ? identity() : new EditImpl<>(path, x -> transform.apply(value, x));
  }

  /**
   * Identity edit — returns its input unchanged. Used internally by the {@code overIfPresent}
   * factories when the carried value is {@code null}, so a sparse-PATCH composition can keep its
   * slot count visible without each null check distorting the surrounding shape.
   */
  @SuppressWarnings("unchecked")
  static <S> Edit<S> identity() {
    // A shared singleton (safe: stateless) so Telescope.all's fusion pass can recognize and skip
    // identity slots instead of treating each fresh lambda as an opaque, unfusible edit.
    return (Edit<S>) EditIdentity.INSTANCE;
  }

  /**
   * Run this edit against {@code s}. Called by {@link Telescope#all(Edit[])} when it folds the
   * edits into a single {@code Function<S, S>}.
   */
  S apply(S s);
}
