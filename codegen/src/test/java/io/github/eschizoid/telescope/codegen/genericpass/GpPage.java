package io.github.eschizoid.telescope.codegen.genericpass;

import io.github.eschizoid.telescope.annotations.Bridge;

/**
 * A source that passes a variable of its own through to the superclass.
 *
 * @param <X> the values' type
 */
@Bridge(GpPageDto.class)
public class GpPage<X> extends GpBase<X> {}
