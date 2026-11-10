package io.github.telescopeorganization.telescope.ownbridge;

import static io.github.telescopeorganization.telescope.Telescope.mapper;
import static io.github.telescopeorganization.telescope.mapping.Mapping.to;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import io.github.telescopeorganization.telescope.conversion.Mapper;

/** A type holding itself, whose own bridge upper-cases its label. */
public record ObNode(String label, ObNode next) {
  @Bridge
  public static final Mapper<ObNode, ObNodeDto> DTO = mapper(
    ObNode.class,
    ObNodeDto.class,
    to(ObNode::label, ObNodeDto::label, ObUpperFn.class)
  );
}
