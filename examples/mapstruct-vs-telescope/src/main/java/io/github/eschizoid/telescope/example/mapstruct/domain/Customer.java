package io.github.eschizoid.telescope.example.mapstruct.domain;

/**
 * Source-side customer. The {@code email} component is the one we rename in the head-to-head — it
 * maps to {@code CustomerDto.contactEmail}, so both frameworks must spell that correspondence
 * explicitly. telescope spells it with a method reference ({@code Customer::email}); MapStruct
 * spells it with a string ({@code @Mapping(source = "email", ...)}).
 */
public record Customer(String name, String email) {}
