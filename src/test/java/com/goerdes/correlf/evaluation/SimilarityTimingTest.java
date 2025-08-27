package com.goerdes.correlf.evaluation;

import com.goerdes.correlf.components.Coderec;
import com.goerdes.correlf.components.ElfHandler;
import com.goerdes.correlf.components.ElfWrapperFactory;
import com.goerdes.correlf.components.MinHashProvider;
import com.goerdes.correlf.db.FileEntity;
import com.goerdes.correlf.db.FileRepo;
import com.goerdes.correlf.model.ElfWrapper;
import com.goerdes.correlf.utils.Setup;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.goerdes.correlf.model.RepresentationType.CODE_REGION_LIST;
import static com.goerdes.correlf.model.RepresentationType.PROGRAM_HEADER_VECTOR;
import static com.goerdes.correlf.model.RepresentationType.STRING_MINHASH;
import static com.goerdes.correlf.services.FileComparisonService.computeJaccardScore;
import static com.goerdes.correlf.services.FileComparisonService.computeProgramHeaderSimilarity;
import static com.goerdes.correlf.utils.ByteUtils.deserializeCodeIntervals;
import static com.goerdes.correlf.utils.ByteUtils.unpackBytesToDoubles;
import static com.goerdes.correlf.utils.ByteUtils.unpackBytesToInts;
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
        List<Coderec.Interval> refCode = deserializeCodeIntervals(
                ref.findRepresentationByType(CODE_REGION_LIST).orElseThrow().getData());
        double[] refPH = unpackBytesToDoubles(
                ref.findRepresentationByType(PROGRAM_HEADER_VECTOR).orElseThrow().getData());

        var minHash = minHashProvider.get();

        List<FileEntity> all = fileRepo.findAll();
        int n = all.size();

        int[][] dbStrings = new int[n][];
        List<Coderec.Interval>[] dbCode = new List[n];
        double[][] dbPH = new double[n][];
        String[] filenames = new String[n];

        for (int i = 0; i < n; i++) {
            FileEntity e = all.get(i);
            filenames[i] = e.getFilename();
            dbStrings[i] = unpackBytesToInts(
                    e.findRepresentationByType(STRING_MINHASH).orElseThrow().getData());
            dbCode[i] = deserializeCodeIntervals(
                    e.findRepresentationByType(CODE_REGION_LIST).orElseThrow().getData());
            dbPH[i] = unpackBytesToDoubles(
                    e.findRepresentationByType(PROGRAM_HEADER_VECTOR).orElseThrow().getData());
        }

        double sink = 0.0;
        for (int w = 0; w < 2; w++) {
            for (int i = 0; i < n; i++) {
                sink += minHash.similarity(refStrings, dbStrings[i]);
                sink += computeJaccardScore(refCode, dbCode[i]);
                sink += computeProgramHeaderSimilarity(refPH, dbPH[i]);
            }
        }

        long totalString = 0L, totalCode = 0L, totalPH = 0L;
        var slowest = new ArrayList<PerComp>(Math.min(5, n));

        for (int i = 0; i < n; i++) {
            long a, stringNanos, codeNanos, phNanos;

            a = System.nanoTime();
            sink += minHash.similarity(refStrings, dbStrings[i]);
            stringNanos = System.nanoTime() - a;

            a = System.nanoTime();
            sink += computeJaccardScore(refCode, dbCode[i]);
            codeNanos = System.nanoTime() - a;

            a = System.nanoTime();
            sink += computeProgramHeaderSimilarity(refPH, dbPH[i]);
            phNanos = System.nanoTime() - a;

            totalString += stringNanos;
            totalCode   += codeNanos;
            totalPH     += phNanos;

            slowest.add(new PerComp(filenames[i], stringNanos + codeNanos + phNanos, stringNanos, codeNanos, phNanos));
        }

        slowest.sort(Comparator.comparingLong(p -> -p.totalNanos));

        System.out.printf("%s: DB comparisons=%d%n", label, n);
        System.out.printf("  Reference prep (parse+entity):       %.3f ms%n", ms(refPrepNanos));
        System.out.printf("  Avg per comparison (STRING only):    %.3f µs%n", us(avg(totalString, n)));
        System.out.printf("  Avg per comparison (CODE regions):   %.3f µs%n", us(avg(totalCode, n)));
        System.out.printf("  Avg per comparison (PROG headers):   %.3f µs%n", us(avg(totalPH, n)));

        System.out.println("  Slowest 5 comparisons (TOTAL µs | string | code | progHdr):");
        for (int i = 0; i < Math.min(5, slowest.size()); i++) {
            var p = slowest.get(i);
            System.out.printf("    - %s : %.3f | %.3f | %.3f | %.3f%n",
                    p.file, us(p.totalNanos), us(p.stringNanos), us(p.codeNanos), us(p.phNanos));
        }

        if (sink == 42.0) {
            System.out.println("ignore sink: " + sink);
        }
    }

    private static double ms(long nanos) { return nanos / 1_000_000.0; }
    private static double us(long nanos) { return nanos / 1_000.0; }
    private static long avg(long sum, int n) { return n == 0 ? 0L : sum / n; }

    private record PerComp(String file, long totalNanos, long stringNanos, long codeNanos, long phNanos) {}
}
