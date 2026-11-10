package io.github.telescopeorganization.telescope.benchmarks;

import java.util.List;

/**
 * The same six properties as {@link BicOrder}, each the type it has on both sides of the control
 * pair, so converting it runs no built-in conversion.
 */
public record BicPlain(String id, String total, String status, String paid, String quantity, List<String> tags) {}
