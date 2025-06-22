package com.goerdes.correlf.services;

import com.goerdes.correlf.components.Coderec.CodeRegion;
import com.goerdes.correlf.components.MinHashProvider;
import com.goerdes.correlf.db.FileEntity;
import com.goerdes.correlf.model.FileComparison;
import com.goerdes.correlf.model.RepresentationType;
import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;

import static com.goerdes.correlf.model.RepresentationType.*;
import static com.goerdes.correlf.utils.ByteUtils.*;
import static java.util.Comparator.comparingLong;

@Service
@RequiredArgsConstructor
public class FileComparisonService {

    private final MinHashProvider minHashProvider;

    /**
     * Weights for each RepresentationType.
     */
    private final Map<RepresentationType, Double> weights = new EnumMap<>(Map.of(
            ELF_HEADER_VECTOR, 0.0,
            STRING_MINHASH,    0.4,
            SECTION_SIZE_VECTOR, 0.6
    ));


    /**
     * Compares a reference file against a target file and returns
     * a FileComparison for the target.
     *
     * @param referenceFile the original file entity to compare from
     * @param targetFile    the file entity to compare against the reference
     * @return a FileComparison describing the similarity result for the target file
     */
    public FileComparison compareFiles(FileEntity referenceFile, FileEntity targetFile) {
        Map<RepresentationType, Double> comparisons = new HashMap<>();

        boolean bothParsed = referenceFile.isParsingSuccessful() && targetFile.isParsingSuccessful();

        if(bothParsed) {
            double headerSim = getHeaderSim(referenceFile, targetFile);
            comparisons.put(ELF_HEADER_VECTOR, headerSim);

            double sectionSizeSim = getSectionSizeSim(referenceFile, targetFile);
            comparisons.put(SECTION_SIZE_VECTOR, sectionSizeSim);
        }

        double stringSim = getStringSim(referenceFile, targetFile);
        comparisons.put(STRING_MINHASH, stringSim);

        double codeRegionSim =
                computeJaccardScore(deserializeCodeRegions(referenceFile.findRepresentationByType(CODE_REGION_LIST).orElseThrow().getData()),
                        deserializeCodeRegions(targetFile.findRepresentationByType(CODE_REGION_LIST).orElseThrow().getData()));
        comparisons.put(CODE_REGION_LIST, codeRegionSim);

        double simScore = comparisons.entrySet().stream()
                .mapToDouble(e -> weights.getOrDefault(e.getKey(), 0.0) * e.getValue())
                .sum();

        return new FileComparison() {{
            setFileName(targetFile.getFilename());
            setSecondFileName(referenceFile.getFilename());
            setComparisonDetails(comparisons);
            setSimilarityScore(bothParsed ? simScore : (0.5 * codeRegionSim + 0.5 * stringSim));
        }};
    }

    double getSectionSizeSim(FileEntity referenceFile, FileEntity targetFile) {
        return cosineSimilarity(
                unpackBytesToDoubles(
                        referenceFile.findRepresentationByType(SECTION_SIZE_VECTOR).orElseThrow().getData()
                ), unpackBytesToDoubles(
                        targetFile.findRepresentationByType(SECTION_SIZE_VECTOR).orElseThrow().getData()
                )
        );
    }

    double getStringSim(FileEntity referenceFile, FileEntity targetFile) {
        return minHashProvider.get().similarity(
                unpackBytesToInts(
                        referenceFile.findRepresentationByType(STRING_MINHASH).orElseThrow().getData()
                ), unpackBytesToInts(
                        targetFile.findRepresentationByType(STRING_MINHASH).orElseThrow().getData()
                )
        );
    }

    double getHeaderSim(FileEntity referenceFile, FileEntity targetFile) {
        return cosineSimilarity(
                unpackBytesToDoubles(
                        referenceFile.findRepresentationByType(ELF_HEADER_VECTOR).orElseThrow().getData()
                ), unpackBytesToDoubles(
                        targetFile.findRepresentationByType(ELF_HEADER_VECTOR).orElseThrow().getData()
                )
        );
    }

    /**
     * Computes the cosine similarity between two feature vectors:
     * cosine = (A·B) / (||A|| * ||B||)
     *
     * @param a first feature vector
     * @param b second feature vector
     * @return the cosine similarity
     * @throws IllegalArgumentException if vectors differ in length or are zero‐length
     */
    public static double cosineSimilarity(double[] a, double[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("Vector lengths must match");
        }
        if (a.length == 0) {
            throw new IllegalArgumentException("Vectors must not be empty");
        }

        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * Computes Jaccard score = |A ∩ B| / |A ∪ B| over code‐region byte intervals.
     */
    public static double computeJaccardScore(List<CodeRegion> a, List<CodeRegion> b) {
        List<Interval> ia = mergeAndNormalize(a);
        List<Interval> ib = mergeAndNormalize(b);

        long inter = 0;
        int i = 0, j = 0;
        while (i < ia.size() && j < ib.size()) {
            Interval A = ia.get(i), B = ib.get(j);
            long lo = Math.max(A.start, B.start);
            long hi = Math.min(A.end,   B.end);
            if (lo < hi) inter += hi - lo;
            if (A.end < B.end) i++; else j++;
        }

        long sumA = ia.stream().mapToLong(iv -> iv.end - iv.start).sum();
        long sumB = ib.stream().mapToLong(iv -> iv.end - iv.start).sum();
        long uni  = sumA + sumB - inter;
        return uni == 0 ? 1.0 : (double) inter / uni;
    }

    /**
     * Merge overlapping code regions into disjoint intervals.
     */
    private static List<Interval> mergeAndNormalize(List<CodeRegion> regions) {
        return regions.stream()
                .map(r -> new Interval(r.start(), r.end()))
                .sorted(comparingLong(iv -> iv.start))
                .collect(ArrayList::new, (out, iv) -> {
                    if (out.isEmpty() || out.getLast().end < iv.start) {
                        out.add(iv);
                    } else { // overlap --> extend interval
                        Interval last = out.getLast();
                        last.end = Math.max(last.end, iv.end);
                    }
                }, ArrayList::addAll);
    }

    @AllArgsConstructor
    private static class Interval {
        long start, end;
    }

}
