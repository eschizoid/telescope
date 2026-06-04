package com.github.eschizoid.telescope.focus;

import com.github.eschizoid.telescope.annotations.Focus;
import java.util.List;

/**
 * Test record with a collection component — exercises the generated {@code each<Component>}
 * traversal constant ({@code FocusTeamFocus.eachMembers}).
 */
@Focus
record FocusTeam(String name, List<FocusPerson> members) {}
