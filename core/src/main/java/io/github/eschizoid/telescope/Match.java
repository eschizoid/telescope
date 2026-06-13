package io.github.eschizoid.telescope;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Compile-checked sealed-type dispatcher. Builds a {@code Function<P, R>} that pattern-matches on
 * the runtime class of {@code P} against a per-permit set of handlers, then verifies at the {@link
 * #exhaustive()} terminal that every permit was covered.
 *
 * <p>Uses {@link Class#getPermittedSubclasses()} (Java 17 JEP 409) to discover the sealed hierarchy
 * at construction time. MapStruct cannot reach this primitive — its annotation model pre-dates
 * sealed types (Java 11 floor). The exhaustiveness guarantee is enforced at the {@code
 * .exhaustive()} call rather than via the compile-time switch exhaustiveness check, which means the
 * same dispatcher works under {@code --release 17} (PR #80's cross-compile target) without the
 * {@code switch} on type-patterns (Java 21+) the language gives you for free.
 *
 * <pre>{@code
 * sealed interface Payment permits CreditCard, BankTransfer, Crypto {}
 *
 * Function<Payment, PaymentDto> dispatch = Match.<Payment, PaymentDto>of(Payment.class)
 *     .when(CreditCard.class,    CreditCardBridge.BRIDGE::forward)
 *     .when(BankTransfer.class,  BankBridge.BRIDGE::forward)
 *     .when(Crypto.class,        CryptoBridge.BRIDGE::forward)
 *     .exhaustive();   // ↑ throws if any permit lacks a .when(...) handler
 * }</pre>
 *
 * <p>Composes naturally with {@link io.github.eschizoid.telescope.conversion.Mapper#asTelescope()
 * Mapper.asTelescope()} so the dispatcher can be chained into a longer {@link Telescope} path:
 *
 * <pre>{@code
 * Telescope.of(Order.class)
 *     .field(Order::payment)
 *     .update(order, dispatch.compose(... ).andThen( ... ));
 * }</pre>
 *
 * @param <P> the sealed parent type
 * @param <R> the dispatch result type
 */
public final class Match<P, R> {

  private final Class<P> sealedRoot;
  private final Map<Class<? extends P>, Function<? super P, ? extends R>> handlers;

  private Match(final Class<P> sealedRoot, final Map<Class<? extends P>, Function<? super P, ? extends R>> handlers) {
    this.sealedRoot = sealedRoot;
    this.handlers = handlers;
  }

  /**
   * Open a builder for a dispatcher over the sealed hierarchy rooted at {@code sealedRoot}. Throws
   * {@link IllegalArgumentException} if the class is not sealed.
   */
  public static <P, R> Match<P, R> of(final Class<P> sealedRoot) {
    if (!sealedRoot.isSealed()) throw new IllegalArgumentException(
      "Match.of(" +
        sealedRoot.getName() +
        "): root class is not sealed. " +
        "Match requires a sealed hierarchy so .exhaustive() can verify coverage."
    );
    return new Match<>(sealedRoot, new LinkedHashMap<>());
  }

  /**
   * Register a handler for one permit of the sealed hierarchy. Returns a new builder — chains
   * compose left-to-right. Same handler registered twice throws at the {@code when} call.
   */
  public <S extends P> Match<P, R> when(final Class<S> caseClass, final Function<? super S, ? extends R> handler) {
    if (handlers.containsKey(caseClass)) throw new IllegalArgumentException(
      "Match.when(" +
        caseClass.getName() +
        "): handler already registered. " +
        "Each permit of the sealed hierarchy may have at most one handler."
    );
    final var next = new LinkedHashMap<>(handlers);
    @SuppressWarnings({ "unchecked", "rawtypes" })
    final Function<? super P, ? extends R> typed = (Function) handler;
    next.put(caseClass, typed);
    return new Match<>(sealedRoot, next);
  }

  /**
   * Verify exhaustiveness and produce the dispatch function. Reads the sealed root's permitted
   * subclasses via {@link Class#getPermittedSubclasses()} and throws if any permit lacks a
   * registered handler — naming the missing permits in the error message.
   *
   * <p>The returned {@link Function} dispatches by {@link Class#isInstance(Object)} test in
   * registration order. At runtime, the first matching handler wins (so a permit handler can be
   * registered redundantly under a parent permit if the user wants that semantics; the typical case
   * is one handler per permit).
   */
  public Function<P, R> exhaustive() {
    final var permits = sealedRoot.getPermittedSubclasses();
    final var registered = handlers.keySet();
    // Walk `permits` directly (source-declaration order from the JVM), not Set.of(permits) — the
    // HashSet iteration order would be non-deterministic across JVM runs, leading to differently-
    // ordered error messages on each invocation.
    final var missing = new java.util.ArrayList<String>();
    for (final var permit : permits) {
      if (!registered.contains(permit)) missing.add(permit.getSimpleName());
    }
    if (!missing.isEmpty()) throw new IllegalStateException(
      "Match.exhaustive(" +
        sealedRoot.getSimpleName() +
        "): " +
        "no handler registered for permitted subclass(es): " +
        String.join(", ", missing) +
        ". Add a .when(<class>, handler) call for each, or use .partial() to allow missing handlers."
    );
    final var snapshot = Map.copyOf(handlers);
    return p -> {
      for (final var entry : snapshot.entrySet()) {
        if (entry.getKey().isInstance(p)) return entry.getValue().apply(p);
      }
      throw new IllegalStateException(
        "Match: input class " +
          (p == null ? "null" : p.getClass().getName()) +
          " does not match any registered permit of " +
          sealedRoot.getSimpleName()
      );
    };
  }

  /**
   * Produce the dispatch function WITHOUT exhaustiveness checking. Use when the sealed hierarchy is
   * known incomplete by design — the returned function throws at dispatch time for unhandled cases
   * with a self-diagnosing message naming the runtime class.
   */
  public Function<P, R> partial() {
    final var snapshot = Map.copyOf(handlers);
    final var rootName = sealedRoot.getSimpleName();
    return p -> {
      for (final var entry : snapshot.entrySet()) {
        if (entry.getKey().isInstance(p)) return entry.getValue().apply(p);
      }
      throw new IllegalStateException(
        "Match.partial(" +
          rootName +
          "): no handler registered for runtime class " +
          (p == null ? "null" : p.getClass().getName())
      );
    };
  }
}
