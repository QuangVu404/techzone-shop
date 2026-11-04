package vn.techzone.infrastructure;

import vn.techzone.application.TechZoneStore;
import java.io.IOException;
import java.nio.file.Path;

/** Persistence boundary for demo state and legacy migration. */
public interface StoreRepository extends AutoCloseable {
    TechZoneStore loadOrCreate() throws IOException;
    void save(TechZoneStore store) throws IOException;
    Path dataFile();
    @Override void close() throws IOException;
}
