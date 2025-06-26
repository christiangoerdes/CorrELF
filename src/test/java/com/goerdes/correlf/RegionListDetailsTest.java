package com.goerdes.correlf;

import com.goerdes.correlf.components.Coderec;
import com.goerdes.correlf.db.FileRepo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.IntSummaryStatistics;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.Map;
import java.util.stream.Collectors;

import static com.goerdes.correlf.model.RepresentationType.CODE_REGION_LIST;
import static com.goerdes.correlf.utils.ByteUtils.deserializeCodeRegions;

@SpringBootTest
@Transactional
public class RegionListDetailsTest {

    @Autowired
    private FileRepo fileRepo;

    @Test
    void codeRegionStatistics() {
        List<List<Coderec.CodeRegion>> allRegions = fileRepo.findAll().stream().filter(e -> e.getFilename().contains(
                "busybox"))
                .map(fe -> deserializeCodeRegions(
                        fe.findRepresentationByType(CODE_REGION_LIST)
                                .orElseThrow().getData()))
                .toList();

        IntSummaryStatistics listStats = allRegions.stream()
                .mapToInt(List::size)
                .summaryStatistics();
        System.out.printf("Files: %d, regions per file → avg=%.2f, min=%d, max=%d%n",
                allRegions.size(),
                listStats.getAverage(),
                listStats.getMin(),
                listStats.getMax()
        );

        Map<String, Long> tagFreq = allRegions.stream()
                .flatMap(List::stream)
                .map(Coderec.CodeRegion::tag)
                .collect(Collectors.groupingBy(t -> t, Collectors.counting()));
        System.out.println("Distinct tags: " + tagFreq.keySet());
        System.out.println("Tag frequencies:");
        tagFreq.forEach((tag, count) ->
                System.out.printf("  %s: %d%n", tag, count)
        );

        LongSummaryStatistics lengthStats = allRegions.stream()
                .flatMap(List::stream)
                .mapToLong(Coderec.CodeRegion::length)
                .summaryStatistics();
        System.out.printf("Region lengths → avg=%.2f, min=%d, max=%d%n",
                lengthStats.getAverage(),
                lengthStats.getMin(),
                lengthStats.getMax()
        );
    }
}
