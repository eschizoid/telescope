package io.github.eschizoid.telescope.codegen.genericpass;

import io.github.eschizoid.telescope.annotations.Bridge;

/**
 * A source that passes a variable of its own through to the superclass, bridged to a target that
 * fixes the same superclass's variable.
 *
 * @param <X> the values' type
 */
@Bridge(GmxStr.class)
public class GmxPage<X> extends GpBase<X> {}
