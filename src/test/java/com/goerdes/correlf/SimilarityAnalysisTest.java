package com.goerdes.correlf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.goerdes.correlf.model.FileComparison;
import com.goerdes.correlf.utils.Setup;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.goerdes.correlf.utils.TestUtils.getMockFile;

@SpringBootTest
public class SimilarityAnalysisTest extends Setup {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testBusybox() throws Exception {
        analyzeAndReport("busybox", "busybox");
    }

    @Test
    void testDropbear() throws Exception {
        analyzeAndReport("dropbear", "dropbear");
    }

    /**
     * Analyzes a file set and prints similarity reports.
     * Only non-family HIGH-similarity files are reported.
     */
    private void analyzeAndReport(String label, String file) throws Exception {
        List<FileComparison> comparisons = fileAnalysisService.analyze(getMockFile(file));

        Map<String, List<FileComparison>> byRating = comparisons.stream()
                .collect(Collectors.groupingBy(c -> c.getSimilarityRating().toLowerCase()));

        int total = comparisons.size();
        printSummary(label, total, byRating);

        List<FileComparison> family = comparisons.stream()
                .filter(c -> c.getFileName().toLowerCase().contains(label.toLowerCase()))
                .collect(Collectors.toList());
        printFamilyStats(label, family);

        List<FileComparison> nonFamilyHigh = byRating.getOrDefault("high", List.of()).stream()
                .filter(c -> !family.contains(c))
                .collect(Collectors.toList());
        printNonFamilyHigh(nonFamilyHigh);

        printDetailedStats(label, family, "medium");
        printDetailedStats(label, family, "low");
    }

    private void printSummary(String label, int total, Map<String, List<FileComparison>> byRating) {
        int high = byRating.getOrDefault("high", List.of()).size();
        int medium = byRating.getOrDefault("medium", List.of()).size();
        int low = byRating.getOrDefault("low", List.of()).size();

        System.out.printf(
                "%s overall: total=%d, high=%d (%.2f%%), medium=%d (%.2f%%), low=%d (%.2f%%)%n",
                label, total,
                high, percentage(high, total),
                medium, percentage(medium, total),
                low, percentage(low, total)
        );
    }

    private void printFamilyStats(String label, List<FileComparison> family) {
        int famTotal = family.size();
        int famHigh = (int) family.stream().filter(c -> "high".equalsIgnoreCase(c.getSimilarityRating())).count();
        int famMedium = (int) family.stream().filter(c -> "medium".equalsIgnoreCase(c.getSimilarityRating())).count();
        int famLow = famTotal - famHigh - famMedium;

        System.out.printf(
                "%s family: total=%d, high=%d (%.2f%%), medium=%d (%.2f%%), low=%d (%.2f%%), high+medium=%d%n",
                label, famTotal,
                famHigh, percentage(famHigh, famTotal),
                famMedium, percentage(famMedium, famTotal),
                famLow, percentage(famLow, famTotal),
                famHigh + famMedium
        );
    }

    private void printNonFamilyHigh(List<FileComparison> nonFamilyHigh) {
        if (nonFamilyHigh.isEmpty()) {
            System.out.println("No non-family files misclassified as HIGH.");
            return;
        }
        System.out.printf("Non-family HIGH-similarity files (%d):%n", nonFamilyHigh.size());
        nonFamilyHigh.forEach(c ->
                System.out.printf("  - %s: %.2f%n", c.getFileName(), c.getSimilarityScore())
        );
    }

    private void printDetailedStats(String label, List<FileComparison> family, String rating) {
        List<FileComparison> list = family.stream()
                .filter(c -> rating.equalsIgnoreCase(c.getSimilarityRating()))
                .toList();
        if (list.isEmpty()) {
            System.out.printf("No %s-similarity %s files found.%n", rating.toUpperCase(), label);
            return;
        }
        double avg = list.stream().mapToDouble(FileComparison::getSimilarityScore).average().orElse(0.0);
        System.out.printf(
                "%s %s-similarity files (%d), avgScore=%.2f:%n",
                label, rating.toUpperCase(), list.size(), avg
        );
        list.forEach(c -> System.out.printf("  - %s: %.2f%n", c.getFileName(), c.getSimilarityScore()));
    }

    private double percentage(int count, int total) {
        return total > 0 ? 100.0 * count / total : 0;
    }
}
