package io.github.telescopeorganization.telescope.benchmarks;

import java.util.List;

/** The control target: {@link BicPlain}'s properties, unchanged. */
public record BicPlainDto(String id, String total, String status, String paid, String quantity, List<String> tags) {}
