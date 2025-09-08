package com.goerdes.correlf.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.goerdes.correlf.components.Coderec;
import com.goerdes.correlf.components.ElfHandler;
import com.goerdes.correlf.components.ElfWrapperFactory;
import com.goerdes.correlf.components.MinHashProvider;
import com.goerdes.correlf.db.FileRepo;
import com.goerdes.correlf.model.ElfWrapper;
import com.goerdes.correlf.model.FileComparison;
import com.goerdes.correlf.model.RepresentationType;
import com.goerdes.correlf.utils.Setup;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

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
 * All-representations export with RESUME:
 * - Preloads all DB files once and deserializes ALL 5 representations.
 * - For each input file: builds all 5 representations, compares against all DB entries,
 *   and writes a JSON with per-representation similarities.
 * - Skips comparisons where the DB filename equals the input filename (identical names).
 * - RESUME: if an output JSON already exists and is readable (non-empty + valid JSON array), it is skipped.
 *   Corrupted/empty outputs are recalculated.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class ExportTrainDataTest extends Setup {

    private static final Path INPUT_DIR   = Paths.get(System.getProperty("inputDir", "data/dataset/Train"));
    private static final Path OUTPUT_DIR  = Paths.get(System.getProperty("outputDir", "data/evaluation/train/all"));
    private static final int  PARALLELISM = Math.max(2, Runtime.getRuntime().availableProcessors());

    @Autowired private FileRepo fileRepo;
    @Autowired private MinHashProvider minHashProvider;
    @Autowired private ElfWrapperFactory elfWrapperFactory;
    @Autowired private ElfHandler elfHandler;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<Map<String,Object>>> LIST_ANY = new TypeReference<>() {};

    @Test
    @Transactional(readOnly = true)
    void exportAllRepresentationsAsJson_resumable() throws Exception {
        if (!Files.isDirectory(INPUT_DIR)) {
            throw new IllegalArgumentException("Input dir does not exist: " + INPUT_DIR.toAbsolutePath());
        }
        Files.createDirectories(OUTPUT_DIR);

        // --- Preload DB: immutable, pre-decoded representations (all five) ---
        record DbReps(
                String filename,
                int[] stringSig,
                List<Coderec.Interval> codeIntervals,
                double[] programHeaderVec,
                double[] elfHeaderVec,
                double[] sectionSizeVec
        ) {}
        final List<DbReps> db = fileRepo.findAll().stream().map(fe -> new DbReps(
                fe.getFilename(),
                fe.findRepresentationByType(STRING_MINHASH).map(r -> unpackBytesToInts(r.getData())).orElse(null),
                fe.findRepresentationByType(CODE_REGION_LIST).map(r -> deserializeCodeIntervals(r.getData())).orElse(List.of()),
                fe.findRepresentationByType(PROGRAM_HEADER_VECTOR).map(r -> unpackBytesToDoubles(r.getData())).orElse(null),
                fe.findRepresentationByType(ELF_HEADER_VECTOR).map(r -> unpackBytesToDoubles(r.getData())).orElse(null),
                fe.findRepresentationByType(SECTION_SIZE_VECTOR).map(r -> unpackBytesToDoubles(r.getData())).orElse(null)
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
        final Instant t0 = Instant.now();

        // --- Parallel processing of inputs ---
        new ForkJoinPool(PARALLELISM).submit(() ->
                inputs.parallelStream().forEach(p -> {
                    final String inName = p.getFileName().toString();
                    final Path out = OUTPUT_DIR.resolve(inName + ".json");

                    try {
                        // Resume: if existing output is valid JSON array, skip
                        if (isOutputValid(out)) {
                            logs.add(String.format("[SKIP] %s (existing valid output)", inName));
                            skipped.incrementAndGet();
                            return;
                        }

                        // Build full wrapper/entity to obtain all five representations once
                        ElfWrapper wrap = elfWrapperFactory.create(asMultipart(p)); // default: all (CODE-REGION if enabled)
                        var inEntity = elfHandler.createEntity(wrap);

                        // Decode INPUT reps once
                        int[] inStrings = inEntity.findRepresentationByType(STRING_MINHASH)
                                .map(r -> unpackBytesToInts(r.getData())).orElse(null);
                        List<Coderec.Interval> inCode = inEntity.findRepresentationByType(CODE_REGION_LIST)
                                .map(r -> deserializeCodeIntervals(r.getData())).orElse(List.of());
                        double[] inPH = inEntity.findRepresentationByType(PROGRAM_HEADER_VECTOR)
                                .map(r -> unpackBytesToDoubles(r.getData())).orElse(null);
                        double[] inEH = inEntity.findRepresentationByType(ELF_HEADER_VECTOR)
                                .map(r -> unpackBytesToDoubles(r.getData())).orElse(null);
                        double[] inSS = inEntity.findRepresentationByType(SECTION_SIZE_VECTOR)
                                .map(r -> unpackBytesToDoubles(r.getData())).orElse(null);

                        // Compare against all DB entries (skip identical filename)
                        List<FileComparison> result = db.stream()
                                .filter(d -> !Objects.equals(d.filename(), inName))
                                .map(d -> {
                                    double stringSim = (inStrings != null && d.stringSig() != null)
                                            ? minHashProvider.get().similarity(inStrings, d.stringSig())
                                            : 0.0;

                                    double codeSim = (!inCode.isEmpty() && !d.codeIntervals().isEmpty())
                                            ? computeJaccardScore(inCode, d.codeIntervals())
                                            : 0.0;

                                    double progHdrSim = (inPH != null && d.programHeaderVec() != null)
                                            // computeProgramHeaderSimilarity mutates arrays -> pass clones
                                            ? computeProgramHeaderSimilarity(inPH.clone(), d.programHeaderVec().clone())
                                            : 0.0;

                                    double elfHeaderSim = safeCosine(inEH, d.elfHeaderVec());
                                    double sectionSizeSim = safeCosine(inSS, d.sectionSizeVec());

                                    Map<RepresentationType, Double> det = new EnumMap<>(RepresentationType.class);
                                    det.put(STRING_MINHASH, stringSim);
                                    det.put(CODE_REGION_LIST, codeSim);
                                    det.put(PROGRAM_HEADER_VECTOR, progHdrSim);
                                    det.put(ELF_HEADER_VECTOR, elfHeaderSim);
                                    det.put(SECTION_SIZE_VECTOR, sectionSizeSim);

                                    FileComparison fc = new FileComparison();
                                    fc.setFileName(d.filename());     // target (DB)
                                    fc.setSecondFileName(inName);     // source (input)
                                    // Keep overall score = MinHash (as in current weighting)
                                    fc.setComparisonDetails(det);

                                    return fc;
                                })
                                .collect(Collectors.toCollection(ArrayList::new));

                        // Write pretty JSON (overwrite if existed but was invalid/empty)
                        MAPPER.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), result);

                        logs.add(String.format("[OK] %s -> %s", inName, out.toAbsolutePath()));
                        processed.incrementAndGet();
                    } catch (Throwable e) {
                        logs.add(String.format("[FAIL] %s: %s", inName, e.getMessage()));
                        failed.incrementAndGet();
                    }
                })
        ).join();

        logs.forEach(System.out::println);
        System.out.printf(
                "Export finished (all reps, resumable): processed=%d, skipped=%d, failed=%d, outDir=%s, took=%s%n",
                processed.get(), skipped.get(), failed.get(), OUTPUT_DIR.toAbsolutePath(), Duration.between(t0, Instant.now()));
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

    /**
     * Output is considered valid if the file exists, is non-empty, and parses as a JSON array.
     * If parsing fails or file is empty/missing, we recompute.
     */
    private static boolean isOutputValid(Path out) {
        try {
            if (!Files.exists(out)) return false;
            if (Files.size(out) <= 2) return false; // empty or "[]"
            // try parse to generic list to avoid model coupling
            MAPPER.readValue(out.toFile(), LIST_ANY);
            return true;
        } catch (Exception parseFailed) {
            return false;
        }
    }
}
