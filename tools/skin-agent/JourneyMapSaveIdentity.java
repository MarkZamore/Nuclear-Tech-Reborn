package lanminecraft.nuclear.skins;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/** Stores a JourneyMap key inside the save so renames and world transfers retain it. */
public final class JourneyMapSaveIdentity {
    private static final String FILE_NAME = "lanminecraft-journeymap-id";
    private static final String UUID_SHAPE = "00000000-0000-0000-0000-000000000000";

    private JourneyMapSaveIdentity() { }

    public static synchronized String getOrCreate(File saveDirectory) {
        if (saveDirectory == null) return null;
        try {
            Path identity = saveDirectory.toPath().resolve(FILE_NAME);
            // The JVM monitor prevents overlapping locks within this process. The file lock
            // keeps other processes from observing a record until its write is complete.
            try (FileChannel file = FileChannel.open(identity, StandardOpenOption.CREATE,
                    StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 FileLock lock = file.lock()) {
                ByteBuffer contents = ByteBuffer.allocate(UUID_SHAPE.length() + 1);
                while (contents.hasRemaining() && file.read(contents) > 0) { }
                String stored = new String(contents.array(), 0, contents.position(), StandardCharsets.US_ASCII);
                if (!isUuidPrefix(stored)) return null;
                if (stored.length() == UUID_SHAPE.length()) {
                    return stored.charAt(14) == '4' && "89ab".indexOf(stored.charAt(19)) >= 0 ? stored : null;
                }

                // An empty or incomplete UUID was never returned by a previous writer.
                String created = UUID.randomUUID().toString();
                ByteBuffer record = ByteBuffer.wrap(created.getBytes(StandardCharsets.US_ASCII));
                file.position(0);
                while (record.hasRemaining()) file.write(record);
                file.force(true);
                return created;
            }
        } catch (IOException | IllegalArgumentException | SecurityException unavailable) {
            return null;
        }
    }

    private static boolean isUuidPrefix(String value) {
        if (value.length() > UUID_SHAPE.length()) return false;
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (UUID_SHAPE.charAt(i) == '-') {
                if (character != '-') return false;
            } else if ((character < '0' || character > '9') && (character < 'a' || character > 'f')) {
                return false;
            }
        }
        return true;
    }
}
