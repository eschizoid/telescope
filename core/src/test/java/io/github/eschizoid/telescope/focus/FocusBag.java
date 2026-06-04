package io.github.eschizoid.telescope.focus;

import io.github.eschizoid.telescope.annotations.Focus;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Test record exercising the {@code each<Component>} traversal constants for {@code Map} values,
 * {@code Optional}, and {@code List} (generates {@code
 * FocusBagFocus.eachLabels/eachNote/eachTags}).
 */
@Focus
record FocusBag(Map<String, String> labels, Optional<String> note, List<String> tags) {}
