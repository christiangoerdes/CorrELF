package com.goerdes.correlf.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.goerdes.correlf.components.Coderec;
import com.goerdes.correlf.components.ElfHandler;
import com.goerdes.correlf.components.ElfWrapperFactory;
import com.goerdes.correlf.components.MinHashProvider;
import com.goerdes.correlf.db.FileRepo;
import com.goerdes.correlf.model.ElfWrapper;
import com.goerdes.correlf.utils.Setup;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.goerdes.correlf.model.RepresentationType.*;
import static com.goerdes.correlf.services.FileComparisonService.computeJaccardScore;
import static com.goerdes.correlf.services.FileComparisonService.computeProgramHeaderSimilarity;
import static com.goerdes.correlf.services.FileComparisonService.cosineSimilarity;
import static com.goerdes.correlf.utils.ByteUtils.*;

/**
 * Export all five representation similarities PLUS:
 *  - binary vector via fixed thresholds (SM/CR/PH),
 *  - final weighted score (sum-to-1),
 *  - per-input timing export (prep/compare/serialize/total + throughput).
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class ExportTestDataTest extends Setup {

    // IO config
    private static final Path INPUT_DIR  = Paths.get(System.getProperty("inputDir", "data/dataset/Train"));
    private static final Path OUTPUT_DIR = Paths.get(System.getProperty("outputDir", "data/evaluation/train/all_with_score"));
    private static final int  PARALLELISM = Math.max(2, Runtime.getRuntime().availableProcessors());

    // Thresholds (taus) for binarization
    private static final double TAU_SM = 0.146484375;
    private static final double TAU_CR = 0.3988839387893677;
    private static final double TAU_PH = 0.8291192054748535;

    // Weights (sum to 1.0)
    private static final double W_SM = 0.6170;
    private static final double W_CR = 0.2946;
    private static final double W_PH = 0.0884;

    @Autowired private FileRepo fileRepo;
    @Autowired private MinHashProvider minHashProvider;
    @Autowired private ElfWrapperFactory elfWrapperFactory;
    @Autowired private ElfHandler elfHandler;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Per-input timing row
    private static final class TimingRow {
        final String inputFile;
        final int comparisons;
        final long msPrep;
        final long msCompare;
        final long msSerialize;
        final long msTotal;
        final double compPerSec;
        TimingRow(String inputFile, int comparisons, long msPrep, long msCompare, long msSerialize, long msTotal) {
            this.inputFile = inputFile;
            this.comparisons = comparisons;
            this.msPrep = msPrep;
            this.msCompare = msCompare;
            this.msSerialize = msSerialize;
            this.msTotal = msTotal;
            this.compPerSec = comparisons > 0 && msCompare > 0 ? (1000.0 * comparisons / msCompare) : 0.0;
        }
    }

    @Test
    @Transactional(readOnly = true)
    void exportWithBinaryAndWeightedScore_resumable_withTiming() throws Exception {
        if (!Files.isDirectory(INPUT_DIR)) {
            throw new IllegalArgumentException("Input dir does not exist: " + INPUT_DIR.toAbsolutePath());
        }
        Files.createDirectories(OUTPUT_DIR);

        // --- Preload DB with decoded representations ---
        record DbReps(
                String filename,
                int[] stringSig,
                List<Coderec.Interval> codeIntervals,
                double[] programHeaderVec
        ) {}
        final List<DbReps> db = fileRepo.findAll().stream().map(fe -> new DbReps(
                fe.getFilename(),
                fe.findRepresentationByType(STRING_MINHASH).map(r -> unpackBytesToInts(r.getData())).orElse(null),
                fe.findRepresentationByType(CODE_REGION_LIST).map(r -> deserializeCodeIntervals(r.getData())).orElse(List.of()),
                fe.findRepresentationByType(PROGRAM_HEADER_VECTOR).map(r -> unpackBytesToDoubles(r.getData())).orElse(null)
        )).toList();

        if (db.isEmpty()) {
            System.out.println("[WARN] Database contains no files.");
            return;
        }

        // --- Collect input files ---
        final List<Path> inputs;
        try (Stream<Path> s = Files.list(INPUT_DIR)) {
            inputs = s.filter(Files::isRegularFile).toList();
        }

        final AtomicInteger processed = new AtomicInteger();
        final AtomicInteger skipped   = new AtomicInteger();
        final AtomicInteger failed    = new AtomicInteger();
        final ConcurrentLinkedQueue<String> logs = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<TimingRow> timings = new ConcurrentLinkedQueue<>();
        final Instant t0 = Instant.now();

        // --- Parallel processing of inputs ---
        new ForkJoinPool(PARALLELISM).submit(() ->
                inputs.parallelStream().forEach(p -> {
                    final String inName = p.getFileName().toString();
                    final Path out = OUTPUT_DIR.resolve(inName + ".json");

                    // timing start
                    final long tStart = System.nanoTime();
                    try {
                        // Resume: if existing output is valid JSON array, skip
                        if (isOutputValid(out)) {
                            logs.add(String.format("[SKIP] %s (existing valid output)", inName));
                            skipped.incrementAndGet();
                            return;
                        }

                        // ----- prep: build wrapper + decode input reps
                        final long tPrep0 = System.nanoTime();
                        ElfWrapper wrap = elfWrapperFactory.create(asMultipart(p));
                        var inEntity = elfHandler.createEntity(wrap);

                        int[] inStrings = inEntity.findRepresentationByType(STRING_MINHASH)
                                .map(r -> unpackBytesToInts(r.getData())).orElse(null);
                        List<Coderec.Interval> inCode = inEntity.findRepresentationByType(CODE_REGION_LIST)
                                .map(r -> deserializeCodeIntervals(r.getData())).orElse(List.of());
                        double[] inPH = inEntity.findRepresentationByType(PROGRAM_HEADER_VECTOR)
                                .map(r -> unpackBytesToDoubles(r.getData())).orElse(null);
                        final long tPrep1 = System.nanoTime();

                        // ----- compare: compute similarities + binary + weighted score
                        final long tCmp0 = tPrep1;
                        List<Map<String, Object>> result = db.stream()
                                .filter(d -> !Objects.equals(d.filename(), inName))
                                .map(d -> {
                                    double stringSim = (inStrings != null && d.stringSig() != null)
                                            ? minHashProvider.get().similarity(inStrings, d.stringSig())
                                            : 0.0;

                                    double codeSim = (!inCode.isEmpty() && !d.codeIntervals().isEmpty())
                                            ? computeJaccardScore(inCode, d.codeIntervals())
                                            : 0.0;

                                    double progHdrSim = (inPH != null && d.programHeaderVec() != null)
                                            ? computeProgramHeaderSimilarity(inPH.clone(), d.programHeaderVec().clone())
                                            : 0.0;

                                    Map<String, Double> det = new LinkedHashMap<>();
                                    det.put("STRING_MINHASH", stringSim);
                                    det.put("CODE_REGION_LIST", codeSim);
                                    det.put("PROGRAM_HEADER_VECTOR", progHdrSim);

                                    int bSM = (Double.isFinite(stringSim)  && stringSim  >= TAU_SM) ? 1 : 0;
                                    int bCR = (Double.isFinite(codeSim)    && codeSim    >= TAU_CR) ? 1 : 0;
                                    int bPH = (Double.isFinite(progHdrSim) && progHdrSim >= TAU_PH) ? 1 : 0;

                                    Map<String, Integer> bin = new LinkedHashMap<>();
                                    bin.put("bin_string_minhash", bSM);
                                    bin.put("bin_code_regions",   bCR);
                                    bin.put("bin_program_header", bPH);

                                    double scoreWeighted = W_SM * bSM + W_CR * bCR + W_PH * bPH;

                                    Map<String, Object> row = new LinkedHashMap<>();
                                    row.put("fileName", d.filename());
                                    row.put("secondFileName", inName);
                                    row.put("comparisonDetails", det);
                                    row.put("binary", bin);
                                    row.put("score_weighted", scoreWeighted);
                                    return row;
                                })
                                .collect(Collectors.toCollection(ArrayList::new));
                        final long tCmp1 = System.nanoTime();

                        // ----- serialize: write JSON
                        final long tSer0 = tCmp1;
                        MAPPER.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), result);
                        final long tSer1 = System.nanoTime();

                        // collect timing
                        int comparisons = result.size();
                        long msPrep = (tPrep1 - tPrep0) / 1_000_000L;
                        long msCompare = (tCmp1 - tCmp0) / 1_000_000L;
                        long msSerialize = (tSer1 - tSer0) / 1_000_000L;
                        long msTotal = (tSer1 - tStart) / 1_000_000L;
                        timings.add(new TimingRow(inName, comparisons, msPrep, msCompare, msSerialize, msTotal));

                        logs.add(String.format("[OK] %s -> %s  (comparisons=%d, total=%d ms, cmp=%d ms, prep=%d ms, ser=%d ms)",
                                inName, out.toAbsolutePath(), comparisons, msTotal, msCompare, msPrep, msSerialize));
                        processed.incrementAndGet();
                    } catch (Throwable e) {
                        logs.add(String.format("[FAIL] %s: %s", inName, e.getMessage()));
                        failed.incrementAndGet();
                    }
                })
        ).join();

        logs.forEach(System.out::println);

        Path parentDir = OUTPUT_DIR.getParent() != null ? OUTPUT_DIR.getParent() : OUTPUT_DIR;
        Files.createDirectories(parentDir);
        Path timingCsv = parentDir.resolve("_timing_export.csv");

        writeTimingCsv(timings, timingCsv);

        long totalMs = Duration.between(t0, Instant.now()).toMillis();
        int totalComparisons = timings.stream().mapToInt(tr -> tr.comparisons).sum();
        double cmpPerSec = totalMs > 0 ? (1000.0 * totalComparisons / totalMs) : 0.0;

        System.out.printf(
                Locale.ROOT,
                "Export finished: processed=%d, skipped=%d, failed=%d, outDir=%s, took=%d ms, comps=%d (%.1f comps/s), timingCsv=%s%n",
                processed.get(), skipped.get(), failed.get(),
                OUTPUT_DIR.toAbsolutePath(), totalMs, totalComparisons, cmpPerSec, timingCsv.toAbsolutePath()
        );
    }

    // --- helpers -------------------------------------------------------------------------

    private static MultipartFile asMultipart(Path p) throws IOException {
        try (InputStream is = Files.newInputStream(p, StandardOpenOption.READ)) {
            return new MockMultipartFile("file", p.getFileName().toString(), "application/octet-stream", is);
        }
    }

    private static double safeCosine(double[] a, double[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0) return 0.0;
        return cosineSimilarity(a, b);
    }

    private static boolean isOutputValid(Path out) {
        try {
            if (!Files.exists(out)) return false;
            if (Files.size(out) <= 2) return false; // empty or "[]"
            MAPPER.readTree(out.toFile());
            return true;
        } catch (Exception parseFailed) {
            return false;
        }
    }

    private static void writeTimingCsv(Collection<TimingRow> rows, Path csvPath) {
        try (BufferedWriter w = Files.newBufferedWriter(csvPath)) {
            w.write("input_file,comparisons,ms_total,ms_prep,ms_compare,ms_serialize,comp_per_sec\n");
            long sumTotal=0, sumPrep=0, sumCmp=0, sumSer=0;
            int sumComp=0;
            for (TimingRow r : rows) {
                w.write(String.format(Locale.ROOT,
                        "%s,%d,%d,%d,%d,%d,%.3f%n",
                        escapeCsv(r.inputFile), r.comparisons, r.msTotal, r.msPrep, r.msCompare, r.msSerialize, r.compPerSec));
                sumTotal += r.msTotal;
                sumPrep  += r.msPrep;
                sumCmp   += r.msCompare;
                sumSer   += r.msSerialize;
                sumComp  += r.comparisons;
            }
            // summary row
            double overallCps = sumTotal > 0 ? (1000.0 * sumComp / sumTotal) : 0.0;
            w.write(String.format(Locale.ROOT,
                    "TOTAL,%d,%d,%d,%d,%d,%.3f%n",
                    sumComp, sumTotal, sumPrep, sumCmp, sumSer, overallCps));
        } catch (IOException e) {
            System.err.println("[WARN] Failed to write timing CSV: " + e.getMessage());
        }
    }

    private static String escapeCsv(String s) {
        if (s == null) return "";
        boolean needs = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r");
        if (!needs) return s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
