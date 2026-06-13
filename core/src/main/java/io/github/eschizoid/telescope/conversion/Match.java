package io.github.eschizoid.telescope.conversion;

import io.github.eschizoid.telescope.internal.optics.Prism;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Compile-checked sealed-type dispatcher. Builds a {@code Function<P, R>} that pattern-matches on
 * the runtime class of {@code P} against a per-permit set of handlers, then verifies at the {@link
 * #exhaustive()} terminal that every permit was covered.
 *
 * <p>Internally each {@code .when(...)} call binds a lattice {@link Prism} (via {@link
 * Prism#downcast(Class)} — the lattice's sealed-narrowing primitive) to its handler, so the
 * sealed-dispatch logic routes through the optic substrate rather than a hand-rolled {@link
 * Function}/{@code Map} table. The public surface stays a plain {@link Function} so call sites
 * don't have to name the lattice's internal types.
 *
 * <p>Uses {@link Class#getPermittedSubclasses()} (Java 17 JEP 409) to discover the sealed hierarchy
 * at {@code .exhaustive()} build time. MapStruct cannot reach this primitive — its annotation model
 * pre-dates sealed types (Java 11 floor). The exhaustiveness guarantee is enforced at the {@code
 * .exhaustive()} call rather than via the compile-time switch exhaustiveness check, which means the
 * same dispatcher works under {@code --release 17} without the {@code switch} on type-patterns
 * (Java 21+) the language gives you for free.
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
 * @param <P> the sealed parent type
 * @param <R> the dispatch result type
 */
public final class Match<P, R> {

  private final Class<P> sealedRoot;
  private final Map<Class<? extends P>, Entry<P, ?, R>> entries;

  // One dispatcher entry — pairs a lattice Prism (narrowing P → S) with the per-permit handler.
  // Kept private so the Prism stays an implementation detail of Match — call-site users never
  // see the internal optic type, matching the project's two-layer mantra.
  private record Entry<P, S extends P, R>(Prism<P, S> prism, Function<? super S, ? extends R> handler) {}

  private Match(final Class<P> sealedRoot, final Map<Class<? extends P>, Entry<P, ?, R>> entries) {
    this.sealedRoot = sealedRoot;
    this.entries = entries;
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
   * compose left-to-right. Same permit registered twice throws at the {@code when} call.
   *
   * <p>Internally creates a {@link Prism} via {@link Prism#downcast(Class)} that narrows the parent
   * {@code P} to the permit subtype {@code S} on hit and rebuilds {@code P} as identity on the
   * reverse direction. Composition through the lattice keeps the dispatcher routed through the
   * optic substrate.
   */
  public <S extends P> Match<P, R> when(final Class<S> caseClass, final Function<? super S, ? extends R> handler) {
    if (entries.containsKey(caseClass)) throw new IllegalArgumentException(
      "Match.when(" +
        caseClass.getName() +
        "): handler already registered. " +
        "Each permit of the sealed hierarchy may have at most one handler."
    );
    final var next = new LinkedHashMap<>(entries);
    final Prism<P, S> prism = Prism.downcast(caseClass);
    next.put(caseClass, new Entry<>(prism, handler));
    return new Match<>(sealedRoot, next);
  }

  /**
   * Verify exhaustiveness and produce the dispatch function. Reads the sealed root's permitted
   * subclasses via {@link Class#getPermittedSubclasses()} and throws if any permit lacks a
   * registered handler — naming the missing permits in the error message.
   *
   * <p>The returned {@link Function} dispatches by walking the registered entries in insertion
   * order and routing through each entry's {@link Prism}; the first prism whose {@code
   * getOption(P)} returns non-empty wins.
   */
  public Function<P, R> exhaustive() {
    final var permits = sealedRoot.getPermittedSubclasses();
    final var registered = entries.keySet();
    // Walk `permits` directly (source-declaration order from the JVM), not Set.of(permits) — the
    // HashSet iteration order would be non-deterministic across JVM runs, leading to differently-
    // ordered error messages on each invocation.
    final var missing = new ArrayList<String>();
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
    return buildDispatch(List.copyOf(entries.values()), sealedRoot);
  }

  /**
   * Produce the dispatch function WITHOUT exhaustiveness checking. Use when the sealed hierarchy is
   * known incomplete by design — the returned function throws at dispatch time for unhandled cases
   * with a self-diagnosing message naming the runtime class.
   */
  public Function<P, R> partial() {
    return buildDispatch(List.copyOf(entries.values()), sealedRoot);
  }

  /**
   * Compose the registered Prism+handler entries into a single dispatch function. Walks each
   * entry's {@link Prism#getOption(Object)} in registration order; the first hit wins.
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static <P, R> Function<P, R> buildDispatch(final List<Entry<P, ?, R>> snapshot, final Class<P> rootClass) {
    final String rootName = rootClass.getSimpleName();
    return p -> {
      for (final var entry : snapshot) {
        final var opt = entry.prism().getOption(p);
        if (opt.isPresent()) {
          final Function handler = entry.handler();
          return (R) handler.apply(opt.get());
        }
      }
      throw new IllegalStateException(
        "Match: no handler matched runtime class " +
          (p == null ? "null" : p.getClass().getName()) +
          " for sealed root " +
          rootName
      );
    };
  }
}
