package io.github.eschizoid.telescope.demo.spring.bughunt.setfield;

/**
 * Element record inside {@code Profile.permissions} ({@code Set<Permission>}). Same-name fields
 * with {@link PermissionEntity} let the deep-mapping engine auto-infer per-component pairs.
 *
 * <p>{@code @Focus} intentionally omitted — see {@link Profile} for the rationale.
 */
public record Permission(String resource, String action) {}
