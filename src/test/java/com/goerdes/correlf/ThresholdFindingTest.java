package com.goerdes.correlf;

import com.goerdes.correlf.model.FileComparison;
import com.goerdes.correlf.model.RepresentationType;
import com.goerdes.correlf.utils.Setup;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.util.List;

import static com.goerdes.correlf.utils.TestUtils.getMockFile;

@SpringBootTest
public class ThresholdFindingTest extends Setup {

    private double findThresholdByMinFamilySim(String familyKey, RepresentationType type) throws IOException {
        List<FileComparison> comparisons = fileAnalysisService.analyze(getMockFile(familyKey));

        List<Double> familySims = comparisons.stream()
                .filter(c -> c.getFileName().toLowerCase().contains(familyKey))
                .map(c -> c.getComparisonDetails().get(type))
                .toList();

        if (familySims.isEmpty()) {
            throw new IllegalStateException("No family members found for: " + familyKey);
        }

        double minFamSim = familySims.stream().min(Double::compareTo).orElse(0.0);
        return Math.round(minFamSim * 10000.0) / 10000.0;
    }

    @Test
    void findBusyboxHeaderThreshold() throws Exception {
        double th = findThresholdByMinFamilySim("busybox", RepresentationType.ELF_HEADER_VECTOR);
        System.out.printf("Best header threshold for busybox: %.4f%n", th);
    }

    @Test
    void findBusyboxStringThreshold() throws Exception {
        double th = findThresholdByMinFamilySim("busybox", RepresentationType.STRING_MINHASH);
        System.out.printf("Best string threshold for busybox: %.4f%n", th);
    }

    @Test
    void findBusyboxSectionSizeThreshold() throws Exception {
        double th = findThresholdByMinFamilySim("busybox", RepresentationType.SECTION_SIZE_VECTOR);
        System.out.printf("Best section-size threshold for busybox: %.4f%n", th);
    }

    @Test
    void findDropbearHeaderThreshold() throws Exception{
        double th = findThresholdByMinFamilySim("dropbear", RepresentationType.ELF_HEADER_VECTOR);
        System.out.printf("Best header threshold for dropbear: %.4f%n", th);
    }

    @Test
    void findDropbearStringThreshold() throws Exception{
        double th = findThresholdByMinFamilySim("dropbear", RepresentationType.STRING_MINHASH);
        System.out.printf("Best string threshold for dropbear: %.4f%n", th);
    }

    @Test
    void findDropbearSectionSizeThreshold() throws Exception{
        double th = findThresholdByMinFamilySim("dropbear", RepresentationType.SECTION_SIZE_VECTOR);
        System.out.printf("Best section-size threshold for dropbear: %.4f%n", th);
    }
}
