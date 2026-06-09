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
    return value == null ? identity() : new EditImpl<>(path, _ -> value);
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
    return value == null ? identity() : new EditImpl<>(path, _ -> mapper.apply(value));
  }

  /**
   * Conditional edit — when {@code value} is non-null, transform each focused leaf via {@code
   * transform.apply(value, currentLeaf)}; otherwise do nothing. Use this when the patch carries a
   * delta or an instruction rather than a replacement.
   *
   * <pre>{@code
   * Edit.overIfPresent(LINE_ITEM_QUANTITIES, req.quantityDelta(), (delta, q) -> q + delta)
   * }</pre>
   */
  static <S, X, V> Edit<S> overIfPresent(
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
  static <S> Edit<S> identity() {
    return s -> s;
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
