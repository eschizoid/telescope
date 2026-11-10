package io.github.telescopeorganization.telescope.spring.samename;

import io.github.telescopeorganization.telescope.annotations.TelescopeMapper;
import io.github.telescopeorganization.telescope.inject.TelescopeProjection;
import io.github.telescopeorganization.telescope.spring.samename.domain.Item;

/** A projection between two types that share a simple name; one of them is written in full. */
@TelescopeMapper
public interface ItemProjection
  extends TelescopeProjection<Item, io.github.telescopeorganization.telescope.spring.samename.api.Item> {}
