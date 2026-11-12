package io.github.eschizoid.telescope.demo.spring;

import io.github.eschizoid.telescope.annotations.TelescopeMapper;
import io.github.eschizoid.telescope.demo.api.ImportedOrder;
import io.github.eschizoid.telescope.demo.api.PlaceOrder;

/**
 * Maps a partner's order to the request the service places. {@code telescope-codegen} writes the
 * Spring bean, which runs {@link SkuCleanup} first and then the bridge generated from {@code
 * RequestMappers.IMPORT}.
 */
@TelescopeMapper(from = ImportedOrder.class, to = PlaceOrder.class, transformers = SkuCleanup.class)
public interface OrderImports {
  PlaceOrder map(ImportedOrder imported);
}
