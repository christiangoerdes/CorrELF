package com.goerdes.correlf;

import com.goerdes.correlf.db.FileEntity;
import com.goerdes.correlf.db.FileRepo;
import com.goerdes.correlf.model.RepresentationType;
import com.goerdes.correlf.model.TwoFileComparison;
import com.goerdes.correlf.services.FileAnalysisService;
import com.goerdes.correlf.services.FileComparisonService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.goerdes.correlf.model.RepresentationType.*;

/**
 * Finds optimal weights for a given fixed threshold.
 */
@SpringBootTest
@Transactional
public class WeightFindingTest {

    @Autowired
    private FileAnalysisService fileAnalysisService;

    @Autowired
    private FileComparisonService comparisonService;

    @Autowired
    private FileRepo fileRepo;

    @BeforeAll
    static void setupData(@Autowired FileAnalysisService service) throws IOException {
        ClassPathResource zipRes = new ClassPathResource("all_elfs.zip");
        MultipartFile zipFile = new MockMultipartFile(
                "file",
                "all_elfs.zip",
                "application/zip",
                zipRes.getInputStream()
        );
        service.importZipArchive(zipFile);
    }

    /**
     * Gathers raw per‐representation similarity values between the first "familyKey" file
     * and all files in the database.
     */
    private List<RawSim> gatherRawSims(String familyKey) {
        FileEntity reference = fileRepo.findAll().stream()
                .filter(f -> f.getFilename().toLowerCase().contains(familyKey))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No reference for: " + familyKey));

        List<RawSim> rawList = new ArrayList<>();
        for (FileEntity target : fileRepo.findAll()) {
            TwoFileComparison comp = comparisonService.compareFiles(reference, target);
            Map<RepresentationType, Double> details = comp.getComparisonDetails();
            boolean isFamily = target.getFilename().toLowerCase().contains(familyKey);
            rawList.add(new RawSim(
                    details.get(ELF_HEADER_VECTOR),
                    details.get(STRING_MINHASH),
                    details.get(SECTION_SIZE_VECTOR),
                    isFamily
            ));
        }
        return rawList;
    }

    /**
     * Given a list of RawSim and a fixed threshold, finds weights (w1, w2, w3 step=0.1,
     * w1+w2+w3=1.0) that maximize true positives, then minimize
     * false positives (non-family sims ≥ threshold).
     */
    @SuppressWarnings("SameParameterValue")
    private void findBestWeightsForThreshold(String familyKey, double threshold) {
        List<RawSim> rawList = gatherRawSims(familyKey);

        int bestTP = -1;
        int bestFP = Integer.MAX_VALUE;
        double[] bestWeights = new double[]{0.0, 0.0, 0.0};

        for (int i = 0; i <= 10; i++) {
            double w1 = i / 10.0;
            for (int j = 0; j <= 10 - i; j++) {
                double w2 = j / 10.0;
                double w3 = 1.0 - w1 - w2;

                int tp = 0, fp = 0;
                for (RawSim r : rawList) {
                    double score = w1 * r.headerSim + w2 * r.stringSim + w3 * r.sectionSim;
                    if (r.isFamily) {
                        if (score >= threshold) {
                            tp++;
                        }
                    } else {
                        if (score >= threshold) {
                            fp++;
                        }
                    }
                }

                if (tp > bestTP || (tp == bestTP && fp < bestFP)) {
                    bestTP = tp;
                    bestFP = fp;
                    bestWeights = new double[]{w1, w2, w3};
                }
            }
        }

        System.out.printf(
                "Threshold=%.3f, best weights for %s: header=%.1f, string=%.1f, section=%.1f => TP=%d, FP=%d%n",
                threshold, familyKey,
                bestWeights[0], bestWeights[1], bestWeights[2],
                bestTP, bestFP
        );
    }

    @Test
    void findBusyboxBestWeightsAtConstantThreshold() {
        findBestWeightsForThreshold("busybox", 0.800);
    }

    @Test
    void findDropbearBestWeightsAtConstantThreshold() {
        findBestWeightsForThreshold("dropbear", 0.800);
    }

    private record RawSim(double headerSim, double stringSim, double sectionSim, boolean isFamily) {
    }
}
