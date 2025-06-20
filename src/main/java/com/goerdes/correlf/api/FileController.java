package com.goerdes.correlf.api;

import com.goerdes.correlf.exception.FileProcessingException;
import com.goerdes.correlf.model.FileComparison;
import com.goerdes.correlf.services.FileAnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class FileController {

    private final FileAnalysisService fileAnalysisService;

    /**
     * Uploads the given file, performs comparison analysis, and returns the list
     * of FileComparison results, optionally filtered by similarity score or rating.
     *
     * @param file the file to analyze
     * @param minScore optional minimum similarity score (inclusive) to include
     * @param maxScore optional maximum similarity score (inclusive) to include
     * @param rating optional similarity rating ("high", "medium", "low") to include
     * @return a ResponseEntity containing the filtered list of FileComparison objects
     * @throws FileProcessingException if analysis fails
     */
    @PostMapping
    public ResponseEntity<List<FileComparison>> uploadAndCompare(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "minScore", required = false) Double minScore,
            @RequestParam(value = "maxScore", required = false) Double maxScore,
            @RequestParam(value = "rating",   required = false) String rating
    ) {
        return ResponseEntity.ok(fileAnalysisService.analyze(file).stream()
                .filter(c -> minScore == null || c.getSimilarityScore() >= minScore)
                .filter(c -> maxScore == null || c.getSimilarityScore() <= maxScore)
                .filter(c -> rating   == null || c.getSimilarityRating().equalsIgnoreCase(rating))
                .toList());
    }

    /**
     * Compares two uploaded files and returns a FileComparison result containing
     * the similarity score and rating.
     *
     * @param file1 the first file to compare
     * @param file2 the second file to compare
     * @return ResponseEntity containing the FileComparison result
     * @throws FileProcessingException if file handling or comparison fails
     */
    @PostMapping("/compare")
    public ResponseEntity<FileComparison> compareFiles(
            @RequestParam("file1") MultipartFile file1,
            @RequestParam("file2") MultipartFile file2
    ) {
        return ResponseEntity.ok(fileAnalysisService.compare(file1, file2));
    }

    /**
     * Accepts a ZIP archive containing multiple binaries, extracts each entry,
     * analyzes it (parses, computes hash, generates representations) and
     * persists it to the database. No comparison results are returned.
     *
     * @param archive the ZIP file with binaries to ingest
     * @return HTTP 204 No Content on success
     * @throws IOException if reading or extracting the ZIP entries fails
     */
    @PostMapping("/upload-zip")
    public ResponseEntity<Void> uploadZipArchive(@RequestParam("file") MultipartFile archive) throws IOException {
        fileAnalysisService.importZipArchive(archive, List.of());
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(FileProcessingException.class)
    public ResponseEntity<String> onError(FileProcessingException ex) {
        return ResponseEntity.badRequest().body(ex.getMessage());
    }

}
