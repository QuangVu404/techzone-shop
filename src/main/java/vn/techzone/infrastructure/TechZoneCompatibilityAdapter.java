package vn.techzone.infrastructure;

import vn.techzone.application.PairCompatibility;
import vn.techzone.application.TechZoneStore;
import vn.techzone.application.port.CompatibilityPort;
import java.util.Objects;

/** Infrastructure adapter for reviewed compatibility records. */
public final class TechZoneCompatibilityAdapter implements CompatibilityPort {
    private final TechZoneStore store;
    public TechZoneCompatibilityAdapter(TechZoneStore store) { this.store = Objects.requireNonNull(store); }
    @Override public PairCompatibility.Result assessPair(String leftId,String rightId) { return store.assessPair(leftId,rightId); }
    @Override public boolean approvedCompatible(String leftId,String rightId) { return store.approvedCompatible(leftId,rightId); }
}
