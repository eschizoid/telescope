package io.github.eschizoid.telescope.nestedextract;

/** Top-level target: a flat field plus a nested POJO filled from a nested map. */
public record CaseListRequest(String caseId, PageDetails pageDetails) {}
