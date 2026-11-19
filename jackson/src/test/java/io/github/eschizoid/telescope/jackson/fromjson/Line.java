package io.github.eschizoid.telescope.jackson.fromjson;

import io.github.eschizoid.telescope.jackson.FromJson;
import java.math.BigDecimal;

/** One line of an order. */
@FromJson
public record Line(String sku, int quantity, BigDecimal price) {}
