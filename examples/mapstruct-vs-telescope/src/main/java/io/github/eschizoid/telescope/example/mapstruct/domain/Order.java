package io.github.eschizoid.telescope.example.mapstruct.domain;

import java.util.List;

/**
 * Source-side order: a nested {@link Customer} plus a {@link LineItem} collection. Deep enough that
 * both the nested-object and the collection recursion paths get exercised, small enough to read at
 * a glance.
 */
public record Order(String id, Customer customer, List<LineItem> lines) {}
