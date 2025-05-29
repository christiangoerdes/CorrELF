package com.goerdes.correlf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.goerdes.correlf.model.FileComparison;
import com.goerdes.correlf.services.FileAnalysisService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * Integration tests for file comparison logic.
 */
@SpringBootTest
public class FileComparisonIntegrationTest {

    private static FileAnalysisService fileAnalysisService;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Ingests the ZIP before any tests run.
     * Uses parameter injection to get the Spring bean into this static method.
     */
    @BeforeAll
    static void setupData(@Autowired FileAnalysisService service) throws IOException {
        fileAnalysisService = service;
        ClassPathResource zipRes = new ClassPathResource("all_elfs.zip");
        MultipartFile zipFile = new MockMultipartFile(
                "file",
                "all_elfs.zip",
                "application/zip",
                zipRes.getInputStream()
        );
        fileAnalysisService.importZipArchive(zipFile);
    }

    @Test
    void testBusybox() throws Exception {
        analyzeAndReport("busybox", "busybox");
    }

    @Test
    void testDropbear() throws Exception {
        analyzeAndReport("dropbear", "dropbear");
    }

    private void analyzeAndReport(String label, String resourcePath) throws Exception {
        List<FileComparison> comparisons = fileAnalysisService.analyze(new MockMultipartFile(
                "file",
                resourcePath,
                "application/octet-stream",
                new ClassPathResource(resourcePath).getInputStream()
        ));

        System.out.println("\n===== " + label.toUpperCase() + " COMPARISONS =====");
        System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(comparisons));

        long total  = comparisons.size();
        long high   = comparisons.stream().filter(c -> "high".equalsIgnoreCase(c.getSimilarityRating())).count();
        long medium = comparisons.stream().filter(c -> "medium".equalsIgnoreCase(c.getSimilarityRating())).count();
        long low    = comparisons.stream().filter(c -> "low".equalsIgnoreCase(c.getSimilarityRating())).count();

        double pctHigh   = total > 0 ? 100.0 * high   / total : 0;
        double pctMedium = total > 0 ? 100.0 * medium / total : 0;
        double pctLow    = total > 0 ? 100.0 * low    / total : 0;

        System.out.printf(
                "%s overall: total=%d, high=%d (%.2f%%), medium=%d (%.2f%%), low=%d (%.2f%%) \n",
                label, total, high, pctHigh, medium, pctMedium, low, pctLow
        );

        List<FileComparison> family = comparisons.stream()
                .filter(c -> c.getFileName().toLowerCase().contains(label.toLowerCase()))
                .toList();

        long famTotal  = family.size();
        long famHigh   = family.stream().filter(c -> "high".equalsIgnoreCase(c.getSimilarityRating())).count();
        long famMedium = family.stream().filter(c -> "medium".equalsIgnoreCase(c.getSimilarityRating())).count();
        long famLow    = family.stream().filter(c -> "low".equalsIgnoreCase(c.getSimilarityRating())).count();

        double famPctHigh   = famTotal > 0 ? 100.0 * famHigh   / famTotal : 0;
        double famPctMedium = famTotal > 0 ? 100.0 * famMedium / famTotal : 0;
        double famPctLow    = famTotal > 0 ? 100.0 * famLow    / famTotal : 0;

        System.out.printf(
                "%s family: total=%d, high=%d (%.2f%%), medium=%d (%.2f%%), low=%d (%.2f%%), high+medium=%d%n \n",
                label, famTotal, famHigh, famPctHigh, famMedium, famPctMedium, famLow, famPctLow, (famHigh + famMedium)
        );

        // Print filenames with LOW similarity in the family
        List<String> lowFamilyNames = family.stream()
                .filter(c -> "low".equalsIgnoreCase(c.getSimilarityRating()))
                .map(FileComparison::getFileName)
                .toList();

        if (!lowFamilyNames.isEmpty()) {
            System.out.println(label + " LOW-similarity files (" + lowFamilyNames.size() + "):");
            lowFamilyNames.forEach(name -> System.out.println("  - " + name));
        } else {
            System.out.println("No LOW-similarity " + label + " files found.");
        }
    }
}
