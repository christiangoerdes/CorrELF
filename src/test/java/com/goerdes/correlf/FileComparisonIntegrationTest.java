package com.goerdes.correlf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.goerdes.correlf.model.FileComparison;
import com.goerdes.correlf.utils.Setup;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static com.goerdes.correlf.utils.TestUtils.getMockFile;

/**
 * Integration tests for file comparison logic.
 */
@SpringBootTest
public class FileComparisonIntegrationTest extends Setup {

    private final ObjectMapper mapper = new ObjectMapper();


    @Test
    void testBusybox() throws Exception {
        analyzeAndReport("busybox", "busybox");
    }

    @Test
    void testDropbear() throws Exception {
        analyzeAndReport("dropbear", "dropbear");
    }

    private void analyzeAndReport(String label, String resourcePath) throws Exception {
        List<FileComparison> comparisons = fileAnalysisService.analyze(getMockFile(resourcePath));

//        System.out.println("\n===== " + label.toUpperCase() + " COMPARISONS =====");
//        System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(comparisons));

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

        // Detailed MEDIUM-similarity report with average score
        List<FileComparison> mediumFamily = family.stream()
                .filter(c -> "medium".equalsIgnoreCase(c.getSimilarityRating()))
                .toList();
        double avgMediumScore = mediumFamily.stream()
                .mapToDouble(FileComparison::getSimilarityScore)
                .average().orElse(0.0);

        if (!mediumFamily.isEmpty()) {
            System.out.printf("%s MEDIUM-similarity files (%d), avgScore=%.2f:%n",
                    label, mediumFamily.size(), avgMediumScore);
            mediumFamily.forEach(c ->
                    System.out.printf("  - %s: %.2f%n", c.getFileName(), c.getSimilarityScore()));
        } else {
            System.out.println("No MEDIUM-similarity " + label + " files found.");
        }

        // Detailed LOW-similarity report with average score
        List<FileComparison> lowFamily = family.stream()
                .filter(c -> "low".equalsIgnoreCase(c.getSimilarityRating()))
                .toList();
        double avgLowScore = lowFamily.stream()
                .mapToDouble(FileComparison::getSimilarityScore)
                .average().orElse(0.0);

        if (!lowFamily.isEmpty()) {
            System.out.printf("%s LOW-similarity files (%d), avgScore=%.2f:%n",
                    label, lowFamily.size(), avgLowScore);
            lowFamily.forEach(c ->
                    System.out.printf("  - %s: %.2f%n", c.getFileName(), c.getSimilarityScore()));
        } else {
            System.out.println("No LOW-similarity " + label + " files found.");
        }

    }
}
