package com.goerdes.correlf.utils;

import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static java.nio.channels.FileChannel.MapMode.READ_ONLY;
import static java.nio.file.StandardOpenOption.READ;

public class StringsUtil {
    private static final int DEFAULT_MIN_LENGTH = 4;

    /**
     * Scans the given file for sequences of printable ASCII characters, using
     * a default minimum length of 4 (same as GNU strings).
     *
     * @param path the filesystem path to the binary or ELF file
     * @return a List of discovered strings in the order they appear in the file
     * @throws IOException if an I/O error occurs while reading the file
     */
    public static List<String> strings(Path path) throws IOException {
        return strings(path, DEFAULT_MIN_LENGTH);
    }

    /**
     * Scans the given file for sequences of printable ASCII characters.
     *
     * @param path the filesystem path to the binary or ELF file
     * @param minLength the minimum number of consecutive printable characters
     *                  required to be considered a "string"
     * @return a List of discovered strings in the order they appear in the file
     * @throws IOException if an I/O error occurs while reading the file
     */
    public static List<String> strings(Path path, int minLength) throws IOException {
        List<String> out = new ArrayList<>();
        try (FileChannel ch = FileChannel.open(path, READ)) {
            MappedByteBuffer buf = ch.map(READ_ONLY, 0, ch.size());
            StringBuilder sb = new StringBuilder();
            for (int i = 0, n = buf.limit(); i < n; i++) {
                byte b = buf.get(i);
                if ((b >= 0x20 && b <= 0x7E) || b == '\t') {
                    sb.append((char) b);
                } else {
                    if (sb.length() >= minLength) {
                        out.add(sb.toString());
                    }
                    sb.setLength(0);
                }
            }
            if (sb.length() >= minLength) {
                out.add(sb.toString());
            }
        }
        return out;
    }
}