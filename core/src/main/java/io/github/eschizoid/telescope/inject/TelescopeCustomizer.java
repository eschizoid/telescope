package io.github.eschizoid.telescope.inject;

/**
 * Configures a generated {@link TelescopeProjection} bean while the container constructs it. The
 * type argument names the projection interface, so the wiring follows a rename instead of depending
 * on a bean name. Every matching customizer runs once, in {@code @Order} order, after the
 * transformers declared on {@code @TelescopeMapper}. Once construction ends, the projection's
 * transformers are fixed.
 *
 * <pre>{@code
 * @Bean
 * TelescopeCustomizer<ShippingProjection> shipping(ShippingCityTransformer city, ShippingReferenceTransformer ref) {
 *   return projection -> projection.addTransformer(city).addTransformer(ref);
 * }
 * }</pre>
 *
 * @param <P> the projection interface annotated with {@code @TelescopeMapper}
 */
@FunctionalInterface
public interface TelescopeCustomizer<P extends TelescopeProjection<?, ?>> {
  /** Register transformers on the projection being constructed. */
  void customize(P projection);
}
