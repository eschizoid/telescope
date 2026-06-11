package io.github.eschizoid.telescope.benchmarks;

import java.util.List;

/** Deep-tier outer record. Mirror of {@link McCompanyBean}. */
public record McCompanyRec(String name, List<McDeptRec> departments) {}
