package io.github.eschizoid.telescope.internal.pairing;

import java.util.ArrayList;
import java.util.List;

/**
 * Which mapper a mapped REST endpoint converts its body through, decided the same way by every
 * container integration. A projection the annotation names through {@code using} is taken as it is,
 * once its type arguments fit the endpoint. Otherwise the candidates for the pair are the
 * projection beans that map it and the {@code Mapper} bean registered for it; one candidate is
 * taken, none falls back to {@code Telescope.mapperForward}, and two or more are refused. The
 * container finds the candidates; this class decides between them and words every refusal.
 */
public final class EndpointMappers {

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
}
