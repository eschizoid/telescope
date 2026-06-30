package io.github.eschizoid.telescope.example.mapstruct.dto;

/**
 * A target with one component — {@code region} — that has no source counterpart on {@code
 * Customer}. It exists to pin the silent-drop footgun: this is the exact shape a target takes the
 * moment a rename strands it without a source.
 */
public record CustomerContactDto(String name, String contactEmail, String region) {}
