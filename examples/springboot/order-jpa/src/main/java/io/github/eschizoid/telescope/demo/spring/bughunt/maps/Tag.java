package io.github.eschizoid.telescope.demo.spring.bughunt.maps;

/**
 * Sub-record exercising the recursive case of {@code Map<String, Tag> ↔ Map<String, TagDto>}.
 * Telescope's deep-mapping engine should recurse into the value type and produce an element-level
 * {@code Iso<Tag, TagDto>}, then lift it through {@code Iso.liftMapValues}.
 */
public record Tag(String label, int weight) {}
