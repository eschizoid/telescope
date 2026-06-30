package io.github.eschizoid.telescope.example.mapstruct.dto;

/**
 * A target with one component — {@code region} — that has no source counterpart on {@code
 * Customer}. It exists to pin the silent-drop footgun: a newly added or drifted target field with
 * no source compiles clean and lands {@code null} at runtime under MapStruct's default policy. (A
 * source rename is the separate, louder case — a compile error.)
 */
public record CustomerContactDto(String name, String contactEmail, String region) {}
