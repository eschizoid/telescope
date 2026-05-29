package org.telescope.focus;

import java.util.List;
import org.telescope.annotations.Focus;

/**
 * Test record with a collection component — exercises the generated {@code each<Component>}
 * traversal constant ({@code FocusTeamFocus.eachMembers}).
 */
@Focus
record FocusTeam(String name, List<FocusPerson> members) {}
