package io.github.eschizoid.telescope.demo.spring.bughunt.maps;

import java.util.Map;

/**
 * Sibling demo record used by the bug-hunt slice for {@code Map<K, V>} field auto-lift through
 * {@code via()} and through pure same-name inference.
 *
 * <ul>
 *   <li>{@code metadata} — same-typed {@code Map<String, String>}, should identity-link by name.
 *   <li>{@code tags} — recursive {@code Map<String, Tag>} ↔ {@code Map<String, TagDto>}, the value
 *       type recurses through {@code autoIso} before {@code Iso.liftMapValues} fires.
 * </ul>
 *
 * <p>The canonical {@code domain.Order} record is left untouched (per slice constraint); this is a
 * dedicated fixture so we can exercise the {@code MAP_VALUES} branch of {@code
 * DeepMap#autoIso}/{@code ContainerShape} without disturbing the JPA demo.
 */
public record MetadataOrder(String id, Map<String, String> metadata, Map<String, Tag> tags) {}
