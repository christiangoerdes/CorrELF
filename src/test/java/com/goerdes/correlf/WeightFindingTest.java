package com.goerdes.correlf;

import com.goerdes.correlf.model.FileComparison;
import com.goerdes.correlf.model.RepresentationType;
import com.goerdes.correlf.services.FileAnalysisService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.multipart.MultipartFile;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.goerdes.correlf.utils.TestUtils.getMockFile;

/**
 * Computes weights for each RepresentationType so that the weighted sum
 * of comparisonDetails best separates the specified family from others
 * at the given threshold. Prints out a Map ready to copy into code.
 */
@SpringBootTest
public class WeightFindingTest {

    @Autowired
    private FileAnalysisService fileAnalysisService;

    @Test
    void computeOptimalWeightsForBusyboxFamily() throws Exception {
        String fileKey = "busybox";
        String familyPrefix = "busybox";
        double threshold = 0.8;
        List<RepresentationType> types = List.of(RepresentationType.STRING_MINHASH,
                RepresentationType.CODE_REGION_LIST, RepresentationType.REGION_COUNT_SIM,
                RepresentationType.AVG_REGION_LENGTH_SIM, RepresentationType.PROGRAM_HEADER_VECTOR);

        MultipartFile upload = getMockFile(fileKey);
        List<FileComparison> all = fileAnalysisService.analyze(upload);
        var family = all.stream()
                .filter(fc -> fc.getFileName().startsWith(familyPrefix))
                .toList();
        var nonFamily = all.stream()
                .filter(fc -> !fc.getFileName().startsWith(familyPrefix))
                .toList();

        Map<RepresentationType, Double> diffs = new EnumMap<>(RepresentationType.class);
        for (var type : types) {
            double meanFam = family.stream()
                    .mapToDouble(fc -> fc.getComparisonDetails().getOrDefault(type, 0.0))
                    .average().orElse(0.0);
            double meanNon = nonFamily.stream()
                    .mapToDouble(fc -> fc.getComparisonDetails().getOrDefault(type, 0.0))
                    .average().orElse(0.0);

            diffs.put(type, Math.max(0.0, meanFam - meanNon));
        }

        double sumDiffs = diffs.values().stream().mapToDouble(Double::doubleValue).sum();
        Map<RepresentationType, Double> weights = new EnumMap<>(RepresentationType.class);
        if (sumDiffs > 0) {
            diffs.forEach((type, diff) ->
                    weights.put(type, diff / sumDiffs)
            );
        } else {
            double eq = 1.0 / types.size();
            types.forEach(t -> weights.put(t, eq));
        }

        System.out.println("private final Map<RepresentationType, Double> fullWeights = new EnumMap<>(RepresentationType.class) {{");
        weights.forEach((type, w) ->
                System.out.printf(Locale.US, "    put(%s, %.3f);%n", type.name(), w));
        System.out.println("}};");
    }
}
