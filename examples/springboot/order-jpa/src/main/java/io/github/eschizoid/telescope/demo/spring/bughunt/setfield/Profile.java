package io.github.eschizoid.telescope.demo.spring.bughunt.setfield;

import java.util.Set;

/**
 * Demo record exercising {@code Set<E>} field navigation — a shape no other demo covers. {@code
 * tags} is a same-typed {@code Set<String>} (identity auto-link to its bean twin); {@code
 * permissions} is a {@code Set<Permission>} so the deep-mapping factory has to lift a sub-mapper
 * through {@link io.github.eschizoid.telescope.internal.optics.Iso#liftSet}.
 *
 * <p>Note: {@code @Focus} is intentionally omitted here pending the {@code java.util.Set} import
 * bug in {@code AbstractTelescopeProcessor#writeInstanceClass} (Step files reference bare {@code
 * Set<...>} but the eager import block omits {@code java.util.Set}). The runtime DSL on {@code
 * Telescope.of(Profile.class)} fully covers the slice meanwhile.
 */
public record Profile(String userId, Set<String> tags, Set<Permission> permissions) {}
