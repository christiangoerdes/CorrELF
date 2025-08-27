package com.goerdes.correlf.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import static com.goerdes.correlf.model.RepresentationType.STRING_MINHASH;
import static com.goerdes.correlf.utils.ByteUtils.unpackBytesToInts;
import static com.goerdes.correlf.components.ElfHandler.mapStringsToTokens;

/**
 * Fast MinHash-only export:
 * - Loads all DB files once, deserializes STRING_MINHASH to int[] and reuses them.
 * - For each input file: extracts only strings via ElfWrapperFactory (coderec is skipped),
 *   builds MinHash signature, compares against all DB signatures, writes JSON.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class MinhashOnlyBatchExportTest extends Setup {

    private static final Path INPUT_DIR   = Paths.get(System.getProperty("inputDir", "data/dataset/Test"));
    private static final Path OUTPUT_DIR  = Paths.get(System.getProperty("outputDir", "data/evaluation/test/all"));
    private static final int  PARALLELISM = Math.max(2, Runtime.getRuntime().availableProcessors());

    @Autowired private FileRepo fileRepo;
    @Autowired private MinHashProvider minHashProvider;
    @Autowired private ElfWrapperFactory elfWrapperFactory;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @Transactional(readOnly = true)
    void exportAll_MinHashOnly_JSON() throws Exception {
        if (!Files.isDirectory(INPUT_DIR)) {
            throw new IllegalArgumentException("Input dir does not exist: " + INPUT_DIR.toAbsolutePath());
        }
        Files.createDirectories(OUTPUT_DIR);

        // Preload DB -> immutable, pre-decoded signatures
        record DbSig(String filename, int[] signature) {}
        final List<DbSig> db = fileRepo.findAll().stream()
                .map(fe -> fe.findRepresentationByType(STRING_MINHASH)
                        .map(rep -> new DbSig(fe.getFilename(), unpackBytesToInts(rep.getData())))
                        .orElse(null))
                .filter(Objects::nonNull)
                .toList();

        if (db.isEmpty()) {
            System.out.println("[WARN] Database contains no STRING_MINHASH representations.");
            return;
        }

        // Collect input files
        final List<Path> inputs;
        try (Stream<Path> s = Files.list(INPUT_DIR)) {
            inputs = s.filter(Files::isRegularFile).toList();
        }

        final AtomicInteger processed = new AtomicInteger();
        final AtomicInteger failed    = new AtomicInteger();
        final ConcurrentLinkedQueue<String> logs = new ConcurrentLinkedQueue<>();
        final Instant t0 = Instant.now();

        // Parallel processing of inputs
        new ForkJoinPool(PARALLELISM).submit(() ->
                inputs.parallelStream().forEach(p -> {
                    final String name = p.getFileName().toString();
                    try {
                        ElfWrapper wrap = elfWrapperFactory.create(asMultipart(p), List.of(RepresentationType.STRING_MINHASH));
                        int[] inSig = minHashProvider.get().signature(mapStringsToTokens(wrap.strings()));

                        // Compare against all DB signatures (MinHash similarity only)
                        List<FileComparison> result = db.stream().map(d -> {
                            double sim = minHashProvider.get().similarity(inSig, d.signature());
                            FileComparison fc = new FileComparison() {{
                                setFileName(d.filename());       // target in DB
                                setSecondFileName(name);         // source = input
                                setSimilarityScore(sim);         // ONLY minhash score
                            }};

                            Map<RepresentationType, Double> det = new EnumMap<>(RepresentationType.class);
                            det.put(STRING_MINHASH, sim);
                            fc.setComparisonDetails(det);

                            Map<RepresentationType, Double> weights = new EnumMap<>(RepresentationType.class);
                            weights.put(STRING_MINHASH, 1.0);
                            fc.setWeights(weights);
                            return fc;
                        }).collect(Collectors.toCollection(ArrayList::new));

                        Path out = OUTPUT_DIR.resolve(name + ".json");
                        MAPPER.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), result);

                        logs.add(String.format("[OK] %s -> %s", name, out.toAbsolutePath()));
                        processed.incrementAndGet();
                    } catch (Throwable e) {
                        logs.add(String.format("[FAIL] %s: %s", name, e.getMessage()));
                        failed.incrementAndGet();
                    }
                })
        ).join();

        logs.forEach(System.out::println);
        System.out.printf("Export finished (MinHash-only): processed=%d, failed=%d, outDir=%s, took=%s%n",
                processed.get(), failed.get(), OUTPUT_DIR.toAbsolutePath(), Duration.between(t0, Instant.now()));
    }

    private static MultipartFile asMultipart(Path p) throws IOException {
        try (InputStream is = Files.newInputStream(p, StandardOpenOption.READ)) {
            return new MockMultipartFile(
                    "file",
                    p.getFileName().toString(),
                    "application/octet-stream",
                    is
            );
        }
    }
}
