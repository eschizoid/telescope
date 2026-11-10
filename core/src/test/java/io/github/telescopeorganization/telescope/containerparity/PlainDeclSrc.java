package io.github.telescopeorganization.telescope.containerparity;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import java.util.Map;

/**
 * A field declared as a plain map. The declaration says nothing about order; the value it holds
 * may, which is the difference the two paths have to decide the same way.
 */
@Bridge(PlainDeclTgt.class)
public record PlainDeclSrc(Map<String, SortedParityA> items) {}
