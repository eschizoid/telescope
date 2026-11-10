package io.github.telescopeorganization.telescope.focus;

import io.github.telescopeorganization.telescope.annotations.Focus;
import java.util.List;

/**
 * Test record with a collection component — exercises the generated {@code each<Component>}
 * traversal constant ({@code FocusTeamFocus.eachMembers}).
 */
@Focus
record FocusTeam(String name, List<FocusPerson> members) {}
