package io.github.eschizoid.telescope.unwalked;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.annotations.Rename;

/** The bridge drops the field that leads back to the parent. */
@Bridge(value = UdXDto.class, renames = @Rename(source = "name", target = "label"), lenient = true, drops = "back")
public record UdX(String name, UdBack back) {}
