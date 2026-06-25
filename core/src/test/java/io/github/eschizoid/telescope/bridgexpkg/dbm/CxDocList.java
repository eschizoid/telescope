package io.github.eschizoid.telescope.bridgexpkg.dbm;

import java.util.ArrayList;

/** Raw (non-generic) Collection subtype on the DB side — its element lives in the supertype. */
@SuppressWarnings("serial")
public class CxDocList extends ArrayList<CxDoc> {}
