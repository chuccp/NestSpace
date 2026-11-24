package com.coke.testapp;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Parses the APK Signing Block without depending on PackageManager. */
final class ApkSigningBlockProbe {
    private static final long V2 = 0x7109871aL;
    private static final long V3 = 0xf05368c0L;
    private static final long V31 = 0x1b93ad61L;
    private static final byte[] MAGIC = "APK Sig Block 42".getBytes(
        java.nio.charset.StandardCharsets.US_ASCII
    );

    static final class Result {
        final long centralDirectoryOffset;
        final long blockSize;
        final Map<Long, String> pairs;
        final boolean passed;

        Result(
            long centralDirectoryOffset,
            long blockSize,
            Map<Long, String> pairs,
            boolean passed
        ) {
            this.centralDirectoryOffset = centralDirectoryOffset;
            this.blockSize = blockSize;
            this.pairs = pairs;
            this.passed = passed;
        }
    }

    private ApkSigningBlockProbe() {}

    static Result parse(File apk) throws Exception {
        try (RandomAccessFile input = new RandomAccessFile(apk, "r")) {
            long eocd = findEocd(input);
            if (eocd < 0) throw new IllegalArgumentException("EOCD missing");
            input.seek(eocd + 12);
            long centralSize = Integer.toUnsignedLong(readInt(input));
            long centralOffset = Integer.toUnsignedLong(readInt(input));
            if (centralOffset < 32 || centralOffset > input.length()) {
                throw new IllegalArgumentException("invalid central directory");
            }

            input.seek(centralOffset - 24);
            long footerSize = readLong(input);
            byte[] magic = new byte[MAGIC.length];
            input.readFully(magic);
            if (!java.util.Arrays.equals(magic, MAGIC)) {
                throw new IllegalArgumentException("signing block magic missing");
            }
            long blockStart = centralOffset - (footerSize + 8);
            if (blockStart < 32 || blockStart >= centralOffset) {
                throw new IllegalArgumentException("invalid signing block offset");
            }
            input.seek(blockStart);
            long headerSize = readLong(input);

            Map<Long, String> pairs = new LinkedHashMap<>();
            long cursor = blockStart + 8;
            long pairsEnd = centralOffset - 24;
            while (cursor + 12 <= pairsEnd) {
                input.seek(cursor);
                long pairSize = readLong(input);
                if (pairSize < 4 || cursor + 8 + pairSize > pairsEnd) {
                    throw new IllegalArgumentException(
                        "invalid pair at " + cursor
                    );
                }
                long id = Integer.toUnsignedLong(readInt(input));
                int valueSize = Math.toIntExact(pairSize - 4);
                byte[] value = new byte[valueSize];
                input.readFully(value);
                pairs.put(id, digest(value));
                cursor += 8 + pairSize;
            }
            boolean aligned =
                headerSize == footerSize &&
                cursor == pairsEnd &&
                centralOffset + centralSize <= input.length();
            boolean signed = pairs.containsKey(V2) || pairs.containsKey(V3);
            return new Result(centralOffset, blockSize(headerSize), pairs, aligned && signed);
        }
    }

    static String describe(Result result) {
        return "cd=" + result.centralDirectoryOffset
            + " block=" + result.blockSize
            + " v2=" + has(result, V2)
            + " v3=" + has(result, V3)
            + " v3.1=" + has(result, V31)
            + " pairs=" + result.pairs;
    }

    private static boolean has(Result result, long id) {
        return result.pairs.containsKey(id);
    }

    private static long blockSize(long value) {
        return value + 8;
    }

    private static long findEocd(RandomAccessFile input) throws Exception {
        long length = input.length();
        int window = (int) Math.min(length, 0xffff + 22);
        byte[] tail = new byte[window];
        input.seek(length - window);
        input.readFully(tail);
        for (int index = tail.length - 22; index >= 0; --index) {
            if (tail[index] == 0x50 &&
                tail[index + 1] == 0x4b &&
                tail[index + 2] == 0x05 &&
                tail[index + 3] == 0x06) {
                return length - window + index;
            }
        }
        return -1;
    }

    private static int readInt(RandomAccessFile input) throws Exception {
        byte[] value = new byte[4];
        input.readFully(value);
        return ByteBuffer.wrap(value)
            .order(ByteOrder.LITTLE_ENDIAN)
            .getInt();
    }

    private static long readLong(RandomAccessFile input) throws Exception {
        byte[] value = new byte[8];
        input.readFully(value);
        return ByteBuffer.wrap(value)
            .order(ByteOrder.LITTLE_ENDIAN)
            .getLong();
    }

    private static String digest(byte[] value) throws Exception {
        byte[] hashed = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < Math.min(12, hashed.length); ++index) {
            result.append(String.format(Locale.US, "%02x", hashed[index]));
        }
        return result.toString();
    }
}
