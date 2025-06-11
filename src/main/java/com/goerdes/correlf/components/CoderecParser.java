package com.goerdes.correlf.components;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.goerdes.correlf.exception.FileProcessingException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parses the output of the external <code>coderec</code> binary into a list of {@link CodeRegion}.
 * The path to the coderec executable is configurable via the <code>coderec.path</code> property.
 */
@Component
public class CoderecParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Resource coderecResource;

    public CoderecParser(@Value("${coderec.location}") Resource coderecResource) {
        this.coderecResource = coderecResource;
    }

    /**
     * Invokes <code>coderec &lt;elfPath&gt;</code>, parses the JSON field
     * <code>range_results</code> and returns one {@link CodeRegion} per entry.
     *
     * @param elfPath path to the ELF binary to analyze
     * @return list of CodeRegion with start, end, length, and tag
     * @throws FileProcessingException if the external process fails or JSON is invalid
     */
    @SuppressWarnings("unchecked")
    public List<CodeRegion> parse(Path elfPath) {
        try {
            Path coderec = coderecResource.getFile().toPath();
            ProcessBuilder pb = new ProcessBuilder(coderec.toString(), elfPath.toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();

            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                r.lines().forEach(out::append);
            }
            if (p.waitFor() != 0) {
                throw new FileProcessingException("coderec failed with exit " + p.exitValue(), null);
            }

            List<List<Object>> rangeResults = (List<List<Object>>) MAPPER.<Map<String, Object>>readValue(out.toString(), new TypeReference<>() {
            }).get("range_results");

            if (rangeResults == null) {
                throw new FileProcessingException("coderec JSON missing range_results", null);
            }

            List<CodeRegion> regions = new ArrayList<>();
            for (List<Object> entry : rangeResults) {
                Map<String, Object> coords = (Map<String, Object>) entry.getFirst();

                regions.add(new CodeRegion(
                        ((Number) coords.get("start")).longValue(),
                        ((Number) coords.get("end")).longValue(),
                        ((Number) entry.get(1)).longValue(),
                        (String) entry.get(2))
                );
            }
            return regions;

        } catch (Exception e) {
            throw new FileProcessingException("Failed to run/parse coderec: " + e.getMessage(), e);
        }
    }

    /**
     * Represents a region in the binary that coderec classified,
     * with start, end, byte-length, and the tag
     */
    public record CodeRegion(long start, long end, long length, String tag) {}
}
