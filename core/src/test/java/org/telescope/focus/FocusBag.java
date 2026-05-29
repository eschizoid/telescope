package org.telescope.focus;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.telescope.annotations.Focus;

/**
 * Test record exercising the {@code each<Component>} traversal constants for {@code Map} values,
 * {@code Optional}, and {@code List} (generates {@code
 * FocusBagFocus.eachLabels/eachNote/eachTags}).
 */
@Focus
record FocusBag(Map<String, String> labels, Optional<String> note, List<String> tags) {}
