package vn.techzone.infrastructure;

import vn.techzone.application.TechZoneStore;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Local demo storage with a single-writer lock and atomic replacement. */
public final class DemoStoreRepository implements StoreRepository {
    private final Path dataFile;
    private final FileChannel lockChannel;
    private final FileLock lock;

    private DemoStoreRepository(Path dataFile, FileChannel lockChannel, FileLock lock) {
        this.dataFile = dataFile; this.lockChannel = lockChannel; this.lock = lock;
    }

    public static DemoStoreRepository open(Path file) throws IOException {
        Path absolute = file.toAbsolutePath().normalize();
        Files.createDirectories(absolute.getParent());
        Path lockPath = absolute.resolveSibling(absolute.getFileName() + ".lock");
        FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock acquired = channel.tryLock();
            if (acquired == null) throw new IOException("Dữ liệu đang được một cửa sổ TechZone khác sử dụng.");
            return new DemoStoreRepository(absolute, channel, acquired);
        } catch (IOException | OverlappingFileLockException ex) {
            channel.close();
            if (ex instanceof IOException io) throw io;
            throw new IOException("Dữ liệu đang được một cửa sổ TechZone khác sử dụng.", ex);
        }
    }

    public TechZoneStore loadOrCreate() throws IOException {
        if (!Files.exists(dataFile)) return new TechZoneStore();
        try (ObjectInputStream input = new ObjectInputStream(new BufferedInputStream(new FileInputStream(dataFile.toFile())))) {
            installFilter(input);
            Object value = input.readObject();
            if (!(value instanceof TechZoneStore store)) throw new IOException("Tệp dữ liệu không thuộc TechZone.");
            return store;
        } catch (ClassNotFoundException | RuntimeException ex) {
            throw new IOException("Không thể đọc dữ liệu TechZone đã lưu.", ex);
        }
    }

    public static void installFilter(ObjectInputStream input) {
            input.setObjectInputFilter(info -> {
                if (info.depth() > 60 || info.references() > 300_000 || info.streamBytes() > 64L * 1024 * 1024
                        || info.arrayLength() > 200_000) return ObjectInputFilter.Status.REJECTED;
                Class<?> type = info.serialClass();
                if (type == null) return ObjectInputFilter.Status.UNDECIDED;
                while (type.isArray()) type = type.getComponentType();
                if (type.isPrimitive()) return ObjectInputFilter.Status.ALLOWED;
                String name = type.getName();
                return name.startsWith("vn.techzone.domain.TechZoneModels$")
                        || name.equals("vn.techzone.domain.UnitAllocation")
                        || name.equals("vn.techzone.application.TechZoneStore")
                        || name.equals("vn.techzone.application.TechZoneStore$AccountRecord")
                        || name.startsWith("java.lang.") || name.startsWith("java.util.") || name.startsWith("java.time.")
                        ? ObjectInputFilter.Status.ALLOWED : ObjectInputFilter.Status.REJECTED;
            });
    }

    public synchronized void save(TechZoneStore store) throws IOException {
        Path temporary = Files.createTempFile(dataFile.getParent(), ".techzone-", ".tmp");
        try {
            try (FileOutputStream file = new FileOutputStream(temporary.toFile()); ObjectOutputStream output = new ObjectOutputStream(file)) {
                output.writeObject(store); output.flush(); file.getFD().sync();
            }
            try { Files.move(temporary, dataFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(temporary, dataFile, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }

    public Path dataFile() { return dataFile; }

    @Override public void close() throws IOException {
        try { if (lock.isValid()) lock.release(); }
        finally { lockChannel.close(); }
    }
}
