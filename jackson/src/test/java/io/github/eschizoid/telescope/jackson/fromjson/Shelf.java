package io.github.eschizoid.telescope.jackson.fromjson;

import io.github.eschizoid.telescope.jackson.FromJson;
import java.util.List;

/** Holds static nested records, which the generated binder reads as the runtime does. */
@FromJson
public record Shelf(Holder.Nested item, List<Holder.Nested> items) {}
