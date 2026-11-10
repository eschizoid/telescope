package io.github.telescopeorganization.telescope.spring.samename;

import io.github.telescopeorganization.telescope.annotations.TelescopeMapper;
import io.github.telescopeorganization.telescope.spring.samename.domain.Item;

/** A plain mapper between two types that share a simple name; one of them is written in full. */
@TelescopeMapper(from = Item.class, to = io.github.telescopeorganization.telescope.spring.samename.api.Item.class)
public interface ItemMapper {
  io.github.telescopeorganization.telescope.spring.samename.api.Item map(Item item);
}
