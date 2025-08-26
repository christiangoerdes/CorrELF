package com.goerdes.correlf.evaluation;

import com.goerdes.correlf.components.MinHashProvider;
import com.goerdes.correlf.db.FileEntity;
import com.goerdes.correlf.model.ElfWrapper;
import com.goerdes.correlf.components.ElfHandler;
import com.goerdes.correlf.components.ElfWrapperFactory;
import com.goerdes.correlf.components.Coderec;
import com.goerdes.correlf.db.FileRepo;
import com.goerdes.correlf.utils.Setup;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.goerdes.correlf.model.RepresentationType.*;
import static com.goerdes.correlf.utils.ByteUtils.*;
import static com.goerdes.correlf.services.FileComparisonService.computeJaccardScore;
import static com.goerdes.correlf.services.FileComparisonService.computeProgramHeaderSimilarity;
import static com.goerdes.correlf.utils.TestUtils.getMockFile;

@Transactional
@SpringBootTest
public class SimilarityTimingTest extends Setup {

    @Autowired private FileRepo fileRepo;
    @Autowired private ElfWrapperFactory factory;
    @Autowired private ElfHandler elfHandler;
    @Autowired private MinHashProvider minHashProvider;

    @Test
    void perfBusybox() throws Exception {
        analyzePerf("busybox", "busybox");
    }

    @Test
    void perfDropbear() throws Exception {
        analyzePerf("dropbear", "dropbear");
    }

    private void analyzePerf(String label, String file) throws Exception {
        MockMultipartFile upload = getMockFile(file);
        long t0 = System.nanoTime();
        ElfWrapper refWrapper = factory.create(upload);
        FileEntity ref = elfHandler.createEntity(refWrapper);
        long refPrepNanos = System.nanoTime() - t0;

        int[] refStrings = unpackBytesToInts(ref.findRepresentationByType(STRING_MINHASH).orElseThrow().getData());
        List<Coderec.Interval> refCode = deserializeCodeIntervals(ref.findRepresentationByType(CODE_REGION_LIST).orElseThrow().getData());
        double[] refPH = unpackBytesToDoubles(ref.findRepresentationByType(PROGRAM_HEADER_VECTOR).orElseThrow().getData());

        var minHash = minHashProvider.get();

        List<FileEntity> all = fileRepo.findAll();
        int n = all.size();

        long totalString = 0L, totalCode = 0L, totalPH = 0L, totalAll = 0L;
        var slowest = new ArrayList<PerComp>();

        for (FileEntity tgt : all) {
            long compStart = System.nanoTime();

            // STRING_MINHASH
            long a = System.nanoTime();
            int[] tgtStrings = unpackBytesToInts(tgt.findRepresentationByType(STRING_MINHASH).orElseThrow().getData());
            minHash.similarity(refStrings, tgtStrings);
            long stringNanos = System.nanoTime() - a;

            // CODE_REGION_LIST
            a = System.nanoTime();
            List<Coderec.Interval> tgtCode = deserializeCodeIntervals(tgt.findRepresentationByType(CODE_REGION_LIST).orElseThrow().getData());
            computeJaccardScore(refCode, tgtCode);
            long codeNanos = System.nanoTime() - a;

            // PROGRAM_HEADER_VECTOR
            a = System.nanoTime();
            double[] tgtPH = unpackBytesToDoubles(tgt.findRepresentationByType(PROGRAM_HEADER_VECTOR).orElseThrow().getData());
            computeProgramHeaderSimilarity(refPH, tgtPH);
            long phNanos = System.nanoTime() - a;

            long compNanos = System.nanoTime() - compStart;

            totalString += stringNanos;
            totalCode   += codeNanos;
            totalPH     += phNanos;
            totalAll    += compNanos;

            slowest.add(new PerComp(tgt.getFilename(), compNanos, stringNanos, codeNanos, phNanos));
        }

        slowest.sort(Comparator.comparingLong(p -> -p.totalNanos));

        System.out.printf("%s: DB comparisons=%d%n", label, n);
        System.out.printf("  Reference prep (parse+entity): %.3f ms%n", ms(refPrepNanos));
        System.out.printf("  Avg per comparison (TOTAL):    %.3f ms%n", ms(avg(totalAll, n)));
        System.out.printf("    ├─ STRING_MINHASH avg:       %.3f ms%n", ms(avg(totalString, n)));
        System.out.printf("    ├─ CODE_REGION_LIST avg:     %.3f ms%n", ms(avg(totalCode, n)));
        System.out.printf("    └─ PROGRAM_HEADER_VECTOR avg:%.3f ms%n", ms(avg(totalPH, n)));

        System.out.println("  Slowest 5 comparisons (TOTAL ms | string | code | progHdr) :");
        for (int i = 0; i < Math.min(5, slowest.size()); i++) {
            var p = slowest.get(i);
            System.out.printf("    - %s : %.3f | %.3f | %.3f | %.3f%n",
                    p.file,
                    ms(p.totalNanos), ms(p.stringNanos), ms(p.codeNanos), ms(p.phNanos));
        }
    }

    private static double ms(long nanos) { return nanos / 1_000_000.0; }
    private static long avg(long sum, int n) { return n == 0 ? 0L : sum / n; }

    private record PerComp(String file, long totalNanos, long stringNanos, long codeNanos, long phNanos) {}
}
