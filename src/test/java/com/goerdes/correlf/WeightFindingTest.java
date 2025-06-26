package com.goerdes.correlf;

import com.goerdes.correlf.model.FileComparison;
import com.goerdes.correlf.model.RepresentationType;
import com.goerdes.correlf.services.FileAnalysisService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Locale;
import java.util.Random;

import static com.goerdes.correlf.utils.TestUtils.getMockFile;

/**
 * Performs a randomized weight search to maximize true positives
 * for the busybox family at a given similarity threshold, while
 * keeping false positives low.
 */
@SpringBootTest
public class WeightFindingTest {

    @Autowired
    private FileAnalysisService fileAnalysisService;

    /**
     * Executes the optimization for the "busybox" family.
     * <ul>
     *   <li>Loads the mock "busybox" file.</li>
     *   <li>Runs randomized weight search across selected metrics.</li>
     *   <li>Prints out the best weights and classification statistics.</li>
     * </ul>
     */
    @Test
    void computeOptimizedWeightsForBusyboxFamily() throws Exception {
        String fileKey       = "busybox";
        String familyPrefix  = "busybox";
        double threshold     = 0.8;
        List<RepresentationType> types = List.of(
                RepresentationType.STRING_MINHASH,
                RepresentationType.CODE_REGION_LIST,
                RepresentationType.PROGRAM_HEADER_VECTOR,
                RepresentationType.SECTION_SIZE_VECTOR,
                RepresentationType.ELF_HEADER_VECTOR
        );

        MultipartFile upload = getMockFile(fileKey);
        List<FileComparison> all = fileAnalysisService.analyze(upload);

        var familyList    = all.stream()
                .filter(fc -> fc.getFileName().startsWith(familyPrefix))
                .toList();
        var nonFamilyList = all.stream()
                .filter(fc -> !fc.getFileName().startsWith(familyPrefix))
                .toList();

        double[] bestWeights = findBestWeights(types, familyList, nonFamilyList, threshold, 20_000,123L);

        System.out.println("private final Map<RepresentationType, Double> fullWeights = new EnumMap<>(RepresentationType.class) {{");
        for (int i = 0; i < types.size(); i++) {
            System.out.printf(Locale.US, "    put(%s, %.3f);%n", types.get(i).name(), bestWeights[i]);
        }
        System.out.println("}};");

        long totalFam = familyList.size();
        long tpFinal = familyList.stream()
                .filter(fc -> dot(types, fc, bestWeights) >= threshold)
                .count();
        long fpFinal = nonFamilyList.stream()
                .filter(fc -> dot(types, fc, bestWeights) >= threshold)
                .count();

        System.out.printf(
                "%s family classified: %d/%d (%.2f%%) true positives, %d false positives%n",
                fileKey, tpFinal, totalFam, 100.0 * tpFinal / totalFam, fpFinal
        );
    }

    /**
     * Runs a multi-objective random search to find weight vectors that
     * prioritize maximizing true positives (tp) on the family set,
     * and secondarily minimizing false positives (fp) on the non-family set.
     *
     * @param types List of RepresentationType metrics to weight.
     * @param familyList FileComparison entries belonging to the family.
     * @param nonFamilyList FileComparison entries outside the family.
     * @param threshold Similarity cutoff for classification.
     * @param iterations Number of random samples to evaluate.
     * @param seed Seed for reproducible randomness.
     * @return An array of normalized weights summing to 1.
     */
    @SuppressWarnings("SameParameterValue")
    private double[] findBestWeights(
            List<RepresentationType> types,
            List<FileComparison> familyList,
            List<FileComparison> nonFamilyList,
            double threshold,
            int iterations,
            long seed
    ) {
        var famVecs = familyList.stream()
                .map(fc -> types.stream()
                        .mapToDouble(t -> fc.getComparisonDetails().getOrDefault(t, 0.0))
                        .toArray())
                .toList();
        var nonFamVecs = nonFamilyList.stream()
                .map(fc -> types.stream()
                        .mapToDouble(t -> fc.getComparisonDetails().getOrDefault(t, 0.0))
                        .toArray())
                .toList();

        Random rnd = new Random(seed);
        int bestTp = -1, bestFp = Integer.MAX_VALUE;
        double[] bestW = new double[types.size()];

        for (int iter = 0; iter < iterations; iter++) {
            double[] w = new double[types.size()];
            double sum = 0;
            for (int i = 0; i < w.length; i++) {
                w[i] = rnd.nextDouble();
                sum += w[i];
            }
            for (int i = 0; i < w.length; i++) {
                w[i] /= sum;
            }

            int tp = 0, fp = 0;
            for (var vec : famVecs)    if (dot(vec, w) >= threshold) tp++;
            for (var vec : nonFamVecs) if (dot(vec, w) >= threshold) fp++;

            if (tp > bestTp || (tp == bestTp && fp < bestFp)) {
                bestTp = tp;
                bestFp = fp;
                System.arraycopy(w, 0, bestW, 0, w.length);
            }
        }

        return bestW;
    }

    /** Dot-product between two same-length arrays. */
    private static double dot(double[] vec, double[] w) {
        double s = 0;
        for (int i = 0; i < vec.length; i++) {
            s += vec[i] * w[i];
        }
        return s;
    }

    /**
     * Applies a weight vector to the given FileComparison's detail metrics.
     *
     * @param types  The ordered metric types corresponding to the weights.
     * @param fc     The FileComparison entry.
     * @param w      The weight array.
     * @return       The weighted sum (similarity score).
     */
    private static double dot(List<RepresentationType> types, FileComparison fc, double[] w) {
        double s = 0;
        var details = fc.getComparisonDetails();
        for (int i = 0; i < types.size(); i++) {
            s += details.getOrDefault(types.get(i), 0.0) * w[i];
        }
        return s;
    }
}
