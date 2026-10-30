package io.github.eschizoid.telescope.spring.samename;

import io.github.eschizoid.telescope.annotations.TelescopeMapper;
import io.github.eschizoid.telescope.spring.samename.domain.Item;

/** A plain mapper between two types that share a simple name; one of them is written in full. */
@TelescopeMapper(from = Item.class, to = io.github.eschizoid.telescope.spring.samename.api.Item.class)
public interface ItemMapper {
  io.github.eschizoid.telescope.spring.samename.api.Item map(Item item);
}
