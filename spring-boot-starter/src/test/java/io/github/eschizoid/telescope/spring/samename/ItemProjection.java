package io.github.eschizoid.telescope.spring.samename;

import io.github.eschizoid.telescope.annotations.TelescopeMapper;
import io.github.eschizoid.telescope.inject.TelescopeProjection;
import io.github.eschizoid.telescope.spring.samename.domain.Item;

/** A projection between two types that share a simple name; one of them is written in full. */
@TelescopeMapper
public interface ItemProjection
  extends TelescopeProjection<Item, io.github.eschizoid.telescope.spring.samename.api.Item> {}
