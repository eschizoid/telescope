package io.github.eschizoid.telescope.demo.nativeimage;

import io.github.eschizoid.telescope.annotations.Focus;
import java.util.List;

/** What the warehouse picks for one order, shelf by shelf. */
@Focus
public record PickList(String orderNumber, List<Pick> picks) {}
