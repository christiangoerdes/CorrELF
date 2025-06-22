package com.goerdes.correlf.utils;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StringsUtilTest {

    @Test
    void testStringsMatchNative() throws Exception {
        Path file = Paths.get("src/test/resources/busybox");

        List<String> utilResult = StringsUtil.strings(file).stream().map(String::trim).toList();

        List<String> nativeResult = runNativeStrings(file).stream().map(String::trim).toList();

        assertEquals(nativeResult.size(), utilResult.size());

        for (int i = 0; i < nativeResult.size(); i++) {
            String expected = nativeResult.get(i);
            String actual = utilResult.get(i);
            int finalI = i;
            assertEquals(expected, actual, () -> String.format("Mismatch at index %d: expected \"%s\" but was \"%s\"", finalI, expected, actual));
        }
    }

    /**
     * Runs `strings <file>` and returns the output lines.
     */
    private List<String> runNativeStrings(Path file) throws Exception {
        Process proc = new ProcessBuilder("strings", file.toString()).redirectErrorStream(true).start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
            List<String> lines = reader.lines().collect(Collectors.toList());
            int exit = proc.waitFor();
            assertEquals(0, exit);
            return lines;
        }
    }
}