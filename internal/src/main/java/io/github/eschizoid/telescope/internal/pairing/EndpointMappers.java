package io.github.eschizoid.telescope.internal.pairing;

import io.github.eschizoid.telescope.internal.BridgeHolderProbe;
import io.github.eschizoid.telescope.internal.Reflective;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Which mapper a mapped REST endpoint converts its body through, decided the same way by every
 * container integration. A projection the annotation names through {@code using} is taken as it is,
 * once its type arguments fit the endpoint. Otherwise the candidates for the pair are the
 * projection beans that map it and the {@code Mapper} bean registered for it; one candidate is
 * taken, none falls back to {@code Telescope.mapperForward}, and two or more are refused. A pair
 * the fallback would map by name alone is refused when no property of the target pairs with one of
 * the source. The container finds the candidates; this class decides between them and words every
 * refusal.
 */
public final class EndpointMappers {

  private static final PairingRules<Type> RULES = new PairingRules<>(new ReflectionProps());

  private EndpointMappers() {}

  /** Where an endpoint's element mapping comes from. */
  public enum Route {
    /** The one projection bean for the pair. */
    PROJECTION,
    /** The {@code Mapper} bean the registry holds for the pair. */
    MAPPER,
    /** {@code Telescope.mapperForward}: a generated bridge or the same-name forward mapping. */
    FORWARD,
  }

  /**
   * The route for {@code source -> target}, given the labels of the projection beans that map the
   * pair and whether the registry holds a {@code Mapper} for it.
   *
   * @throws IllegalArgumentException when more than one candidate maps the pair
   */
  public static Route route(
    final Class<?> source,
    final Class<?> target,
    final List<String> projections,
    final boolean registeredMapper,
    final String annotationName
  ) {
    final var candidates = new ArrayList<>(projections);
    if (registeredMapper) candidates.add(
      "a Mapper<" + source.getSimpleName() + ", " + target.getSimpleName() + "> bean"
    );
    if (candidates.size() > 1) throw new IllegalArgumentException(
      PairingMessages.endpointMapperAmbiguous(source, target, candidates, annotationName)
    );
    if (projections.size() == 1) return Route.PROJECTION;
    return registeredMapper ? Route.MAPPER : Route.FORWARD;
  }

  /**
   * Checks that the projection {@code using} names maps the endpoint's pair. {@code pairSource} and
   * {@code pairTarget} are the projection's {@code TelescopeProjection} type arguments, either null
   * when unresolved; a null {@code target} is not checked. The roles name each side in the message,
   * as the endpoint sees it.
   *
   * @throws IllegalArgumentException when the type arguments are unresolved or do not fit
   */
  public static void checkProjection(
    final String projection,
    final Class<?> pairSource,
    final Class<?> pairTarget,
    final Class<?> source,
    final String sourceRole,
    final Class<?> target,
    final String targetRole
  ) {
    if (pairSource == null || pairTarget == null) throw new IllegalArgumentException(
      PairingMessages.endpointProjectionUnresolved(projection)
    );
    if (pairSource != source) throw new IllegalArgumentException(
      PairingMessages.endpointProjectionSource(projection, pairSource, sourceRole, source)
    );
    if (target != null && pairTarget != target) throw new IllegalArgumentException(
      PairingMessages.endpointProjectionTarget(projection, pairTarget, targetRole, target)
    );
  }

  /**
   * The refusal of the bean of the {@code @Bridge} field {@code field}, whose {@code row}, in the
   * mapper {@code in} where that is not the field's own, calls the service {@code service}, a
   * binary name, of which the application declares no bean. {@code quarkus} adds that a bean only
   * something built at run time looks up is one Quarkus removes unless it is marked unremovable.
   */
  public static String serviceBeanMissing(
    final String field,
    final String service,
    final String row,
    final String in,
    final boolean quarkus
  ) {
    return (
      "The bean of the @Bridge field " +
      field +
      " needs a bean of " +
      service +
      rowOf(row, in) +
      ", and the application declares none. Declare one" +
      (quarkus ? ", or, if only a mapper built at run time looks it up, mark it @Unremovable." : ".")
    );
  }

  /**
   * The refusal of the bean of the {@code @Bridge} field {@code field}, whose {@code row} calls the
   * service {@code service}, of which the application declares the beans {@code names} and none is
   * primary.
   */
  public static String serviceBeansAmbiguous(
    final String field,
    final String service,
    final String row,
    final String in,
    final List<String> names
  ) {
    return (
      "The bean of the @Bridge field " +
      field +
      " needs one bean of " +
      service +
      rowOf(row, in) +
      ", and the application declares " +
      names.size() +
      ", " +
      String.join(" and ", names) +
      ", with none primary. Mark one primary, or keep one bean of that type."
    );
  }

  /** Two {@code @Bridge} fields of classes {@code first} and {@code second} naming one bean. */
  public static String fieldBeanNamedTwice(final String name, final String first, final String second) {
    return (
      "Two @Bridge fields give the bean name " +
      name +
      ", one in " +
      first +
      " and one in " +
      second +
      ". Rename one of the fields or of their classes."
    );
  }

  /**
   * The refusal of an endpoint whose mapper, {@code mapper}, calls {@code service} for {@code row}
   * and is bound to none.
   */
  public static String endpointMapperUnbound(
    final String mapper,
    final String service,
    final String row,
    final String in
  ) {
    return (
      mapper +
      " needs a " +
      service +
      rowOf(row, in) +
      ", and is not bound. Inject the @Bridge field's bean, or bind the mapper with with(...) before declaring" +
      " it as a bean."
    );
  }

  private static String rowOf(final String row, final String in) {
    if (row == null) return "";
    return " for " + row + (in == null ? "" : " in " + in);
  }

  /**
   * Checks that {@code Telescope.mapperForward}, on a pair with no projection or {@code Mapper}
   * bean, fills some property of {@code target}. A generated bridge for the pair, held beside the
   * source or registered by a provider, maps it as its rows say and is not checked; neither is a
   * pair of two sealed interfaces, which the runtime mapper converts case by case. Every other pair
   * the runtime mapper walks by property, a non-sealed interface source by its getters, so its two
   * sides' properties are paired by name as the runtime mapper matches a pair no row configures,
   * and a pair where no target property pairs with a source property is refused: the mapping would
   * leave every property of each value at its default. A pair where some property pairs is taken,
   * whatever the others hold.
   *
   * @param registeredBridge whether a provider registers a generated bridge for the pair
   * @throws IllegalArgumentException when no property of {@code target} pairs with one of {@code
   *     source}
   */
  public static void checkForwardPairs(final Class<?> source, final Class<?> target, final boolean registeredBridge) {
    if (registeredBridge || BridgeHolderProbe.probeFor(source, target).isPresent()) return;
    if (RULES.decidePair(source, target, "", true) instanceof PairDecision.DispatchCases) return;
    final var sourceNames = List.of(Reflective.of(source).names(source));
    final var targetNames = List.of(Reflective.of(target).names(target));
    final var match = PairingRules.matchFields(sourceNames, targetNames, Set.of(), Set.of());
    if (match.matched().isEmpty() && !match.unmatchedTargets().isEmpty()) throw new IllegalArgumentException(
      PairingMessages.endpointNothingPairs(source, sourceNames, target, targetNames)
    );
  }
}
