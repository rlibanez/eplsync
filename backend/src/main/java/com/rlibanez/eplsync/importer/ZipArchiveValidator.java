package com.rlibanez.eplsync.importer;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;

/** Bounds the central directory before ZipFile can allocate its entry index. */
final class ZipArchiveValidator {
    private ZipArchiveValidator() {}
    static void validate(Path path, int maxEntries, long deadline) throws IOException {
        try (var file = new RandomAccessFile(path.toFile(), "r")) {
            int length = (int) Math.min(file.length(), 65557);
            byte[] tail = new byte[length];
            file.seek(file.length() - length);
            file.readFully(tail);
            var bytes = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN);
            int end = -1;
            for (int i = length - 22; i >= 0; i--) {
                if (bytes.getInt(i) == 0x06054b50 && i + 22 + Short.toUnsignedInt(bytes.getShort(i + 20)) == length) {
                    end = i; break;
                }
            }
            if (end < 0) throw invalid();
            long entries = Short.toUnsignedInt(bytes.getShort(end + 10));
            long diskEntries = Short.toUnsignedInt(bytes.getShort(end + 8));
            if (bytes.getShort(end + 4) != 0 || bytes.getShort(end + 6) != 0)
                throw new com.rlibanez.eplsync.exception.CatalogValidationException("Solo se admiten ZIP de un volumen");
            long size = Integer.toUnsignedLong(bytes.getInt(end + 12));
            long start = Integer.toUnsignedLong(bytes.getInt(end + 16));
            long endPosition = file.length() - length + end;
            boolean requires64 = entries == 65535 || diskEntries == 65535 || size == 0xffffffffL || start == 0xffffffffL;
            long locatorPosition = endPosition - 20;
            ByteBuffer locator = null;
            if (locatorPosition >= 0) {
                byte[] data = new byte[20]; file.seek(locatorPosition); file.readFully(data);
                var candidate = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
                if (candidate.getInt(0) == 0x07064b50) locator = candidate;
            }
            if (locator != null) {
                if (locator.getInt(4) != 0 || locator.getInt(16) != 1) throw invalid();
                long recordPosition = locator.getLong(8);
                if (recordPosition < 0 || recordPosition > locatorPosition - 56) throw invalid();
                byte[] data = new byte[56]; file.seek(recordPosition); file.readFully(data);
                var record = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
                long recordSize = record.getLong(4);
                if (record.getInt(0) != 0x06064b50 || recordSize < 44
                        || recordSize != locatorPosition - recordPosition - 12
                        || record.getInt(16) != 0 || record.getInt(20) != 0) throw invalid();
                long expandedEntries = record.getLong(32);
                long expandedSize = record.getLong(40);
                long expandedStart = record.getLong(48);
                if (expandedEntries < 0 || record.getLong(24) != expandedEntries
                        || (entries != 65535 && entries != expandedEntries)
                        || (diskEntries != 65535 && diskEntries != expandedEntries)
                        || (size != 0xffffffffL && size != expandedSize)
                        || (start != 0xffffffffL && start != expandedStart)) throw invalid();
                entries = expandedEntries; size = expandedSize; start = expandedStart;
                endPosition = recordPosition;
            } else if (requires64) throw invalid();
            else if (diskEntries != entries) throw invalid();
            if (entries > maxEntries)
                throw new com.rlibanez.eplsync.exception.CatalogValidationException("El ZIP supera el máximo de 128 entradas");
            // Subtraction avoids overflow in hostile unsigned 64-bit offsets and lengths.
            if (start < 0 || size < 0 || start > endPosition || size != endPosition - start) throw invalid();
            byte[] header = new byte[46];
            long position = start;
            int actualEntries = 0;
            while (position < endPosition) {
                CatalogImportLimits.checkDeadline(deadline);
                if (++actualEntries > maxEntries) throw new com.rlibanez.eplsync.exception.CatalogValidationException("El ZIP supera el máximo de 128 entradas");
                if (position + header.length > endPosition) throw invalid();
                file.seek(position); file.readFully(header);
                var central = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
                if (central.getInt(0) != 0x02014b50 || central.getShort(34) != 0) throw invalid();
                position += 46L + Short.toUnsignedInt(central.getShort(28))
                    + Short.toUnsignedInt(central.getShort(30)) + Short.toUnsignedInt(central.getShort(32));
                if (position > endPosition) throw invalid();
            }
            if (actualEntries != entries) throw invalid();
        }
    }
    private static IllegalArgumentException invalid() {
        return new com.rlibanez.eplsync.exception.CatalogValidationException("El ZIP está dañado o contiene un directorio central inválido");
    }
}
