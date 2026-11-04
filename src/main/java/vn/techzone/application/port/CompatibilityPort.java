package vn.techzone.application.port;

import vn.techzone.application.PairCompatibility;

/** Human-approved compatibility rules and current attribute checks. */
public interface CompatibilityPort {
    PairCompatibility.Result assessPair(String leftId,String rightId);
    boolean approvedCompatible(String leftId,String rightId);
}
