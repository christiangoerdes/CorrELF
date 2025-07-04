package com.goerdes.correlf.utils;

import com.goerdes.correlf.model.ProgramHeader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ProgramHeaderUtils {

    /**
     * Invokes `readelf -lW` on the given ELF file and parses the output
     * to produce a list of {@link ProgramHeader} records.
     *
     * @param elfFile the path to the ELF binary
     * @return a list of program header entries
     * @throws IOException          if an I/O error occurs during the process execution
     * @throws InterruptedException if the external process is interrupted
     */
    public static List<ProgramHeader> extractProgramHeaders(Path elfFile) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("readelf", "-lW", elfFile.toString());
        pb.redirectErrorStream(true);
        Process proc = pb.start();

        List<String> lines;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
            lines = reader.lines().toList();
        }
        proc.waitFor();

        return parseReadelfOutput(lines);
    }

    /**
     * Parses the lines of `readelf -lW` output to extract program headers.
     *
     * @param lines the raw output lines from the readelf invocation
     * @return a list of ProgramHeader entries
     */
    private static List<ProgramHeader> parseReadelfOutput(List<String> lines) {
        List<ProgramHeader> result = new ArrayList<>();
        int idx = 0;

        while (idx < lines.size() && !lines.get(idx).contains("Program Headers:")) {
            idx++;
        }
        if (idx >= lines.size()) return result;

        while (idx < lines.size() && !lines.get(idx).trim().startsWith("Type")) {
            idx++;
        }
        idx++;

        if (idx < lines.size() && lines.get(idx).trim().matches("[- ]+")) {
            idx++;
        }

        for (; idx < lines.size(); idx++) {
            String raw = lines.get(idx).trim();

            if (raw.isEmpty() || raw.startsWith("Section to")) break;

            // skip interpreter comment lines
            if (raw.startsWith("[")) continue;

            String[] cols = raw.split("\\s+");
            // require at least: Type, Offset, VirtAddr, PhysAddr, FileSiz, MemSiz, Flags(>=1), Align
            if (cols.length < 8) continue;

            // the last token must be the alignment in hex
            String alignTok = cols[cols.length - 1];
            if (!alignTok.startsWith("0x") || alignTok.length() < 3) continue;

            try {
                result.add(new ProgramHeader(
                        cols[0], // Type
                        Long.parseLong(cols[1].substring(2), 16), //Offset
                        Long.parseLong(cols[2].substring(2), 16), // VirtAddr
                        Long.parseLong(cols[3].substring(2), 16), // PhysAddr
                        Long.parseLong(cols[4].substring(2), 16), // FileSiz
                        Long.parseLong(cols[5].substring(2), 16), // MemSiz
                        String.join(" ", Arrays.copyOfRange(cols, 6, cols.length - 1)), // Flg
                        Long.parseLong(alignTok.substring(2), 16))); // Align
            } catch (NumberFormatException ignored) {}
        }
        return result;
    }

    public static double[] buildFeatureVector(List<ProgramHeader> list) {
        if(list.isEmpty()) return new double[]{};

        int n = list.size();
        long totalMem = list.stream().mapToLong(ProgramHeader::memSize).sum();
        long totalFile = list.stream().mapToLong(ProgramHeader::fileSize).sum();

        double avg = computeAverage(list);
        double std = computeStdDev(list, avg);
        double[] p = computePercentiles(list);

        double fracExec = computeFlagFraction(list, 'E');
        double fracWrite = computeFlagFraction(list, 'W');
        double fileMemRatio = totalMem > 0 ? (double) totalFile / totalMem : 0.0;

        return new double[]{
                n,                  // 0: segment count
                avg,                // 1: average memSize
                std,                // 2: std-dev memSize
                p[0], p[1], p[2],   // 3–5: 25th, 50th, 75th percentiles
                fracExec,           // 6: fraction executable
                fracWrite,          // 7: fraction writable
                fileMemRatio        // 8: fileSize:memSize ratio
        };
    }

    private static double computeAverage(List<ProgramHeader> list) {
        return list.stream().mapToLong(ProgramHeader::memSize).average().orElse(0.0);
    }

    private static double computeStdDev(List<ProgramHeader> list, double mean) {
        return Math.sqrt(list.stream().mapToDouble(h -> Math.pow(h.memSize() - mean, 2)).sum() / list.size());
    }

    private static double[] computePercentiles(List<ProgramHeader> list) {
        long[] sizes = list.stream().mapToLong(ProgramHeader::memSize).sorted().toArray();
        int n = sizes.length;
        return new double[]{sizes[Math.max(0, (int) Math.round(0.25 * (n - 1)))], sizes[Math.max(0, (int) Math.round(0.50 * (n - 1)))], sizes[Math.max(0, (int) Math.round(0.75 * (n - 1)))]};
    }

    private static double computeFlagFraction(List<ProgramHeader> list, char flag) {
        return list.stream().filter(h -> h.flags().indexOf(flag) >= 0).count() / (double) list.size();
    }

}
