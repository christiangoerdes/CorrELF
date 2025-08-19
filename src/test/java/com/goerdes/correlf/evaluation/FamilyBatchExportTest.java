package com.goerdes.correlf.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.goerdes.correlf.model.FileComparison;
import com.goerdes.correlf.utils.Setup;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

@SpringBootTest
public class FamilyBatchExportTest extends Setup {

    private static final String FAMILY     = System.getProperty("family", "busybox");
    private static final Path INPUT_DIR    = Paths.get(System.getProperty("inputDir", "data/dataset/Train"));
    private static final Path OUTPUT_ROOT  = Paths.get(System.getProperty("outputDir", "data/evaluation/train"));

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void exportFamilySimilarityAsJson() throws Exception {
        runExport();
    }

    /** Scans inputDir for files named "<name>___<origin>", keeps those where <name> startsWith(family), analyzes each,
     *  and writes one JSON per input into outputRoot/<family>/. Continues on per-file errors. */
    private void runExport() throws IOException {
        if (!Files.isDirectory(FamilyBatchExportTest.INPUT_DIR)) {
            throw new IllegalArgumentException("Input dir does not exist: " + FamilyBatchExportTest.INPUT_DIR.toAbsolutePath());
        }
        Path familyOut = FamilyBatchExportTest.OUTPUT_ROOT.resolve(FamilyBatchExportTest.FAMILY.toLowerCase());
        Files.createDirectories(familyOut);

        AtomicInteger processed = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        try (Stream<Path> paths = Files.list(FamilyBatchExportTest.INPUT_DIR)) {
            paths.filter(Files::isRegularFile)
                    .forEach(p -> {
                        String originalName = p.getFileName().toString();
                        if (!matchesFamily(originalName)) return;
                        try {
                            List<FileComparison> result = fileAnalysisService.analyze(asMultipart(p));
                            Path out = familyOut.resolve(originalName + ".json");
                            mapper.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), result);

                            System.out.printf("[OK] %s -> %s%n", originalName, out.toAbsolutePath());
                            processed.incrementAndGet();
                        } catch (Exception e) {
                            System.err.printf("[FAIL] %s: %s%n", originalName, e.getMessage());
                            failed.incrementAndGet();
                        }
                    });
        }

        System.out.printf("Export finished for family='%s': processed=%d, failed=%d, outDir=%s%n",
                FamilyBatchExportTest.FAMILY, processed.get(), failed.get(), familyOut.toAbsolutePath());
    }

    private boolean matchesFamily(String fileName) {
        String token = fileName.toLowerCase();
        int sep = token.indexOf("___");
        if (sep >= 0) token = token.substring(0, sep);
        return token.equals(FamilyBatchExportTest.FAMILY.toLowerCase());
    }

    private MultipartFile asMultipart(Path p) throws IOException {
        try (var is = Files.newInputStream(p)) {
            return new MockMultipartFile(
                    "file",
                    p.getFileName().toString(),
                    "application/octet-stream",
                    is
            );
        }
    }
}
