package io.github.eschizoid.telescope.demo.spring;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.annotations.TelescopeTransformer;
import io.github.eschizoid.telescope.demo.api.ImportedLine;
import io.github.eschizoid.telescope.demo.api.ImportedOrder;
import io.github.eschizoid.telescope.inject.TelescopeTransformation;
import io.github.eschizoid.telescope.inject.Transformation;
import java.util.Locale;

/**
 * Partners type SKUs by hand, with stray spaces and in lower case. This cleans every SKU of an
 * imported order before it is mapped. {@code telescope-codegen} writes the Spring bean.
 */
@TelescopeTransformer
public interface SkuCleanup extends TelescopeTransformation<ImportedOrder, String> {
  @Override
  default Telescope<ImportedOrder, String> path() {
    return Telescope.of(ImportedOrder.class).each(ImportedOrder::lines).field(ImportedLine::sku);
  }

  @Override
  default Transformation<String> transform() {
    return new Transformation<>(null, sku -> sku.strip().toUpperCase(Locale.ROOT));
  }
}
