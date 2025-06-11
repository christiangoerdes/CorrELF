package com.goerdes.correlf.components;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.goerdes.correlf.exception.FileProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Parses the output of the external <code>coderec</code> binary into a list of {@link CodeRegion}.
 * The path to the coderec executable is configurable via the <code>coderec.path</code> property.
 */
@Component
public class CoderecParser {

    private static final Logger log = LoggerFactory.getLogger(CoderecParser.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int BATCH_SIZE = 200;

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
    public List<CodeRegion> parseSingle(Path elfPath) {
        return parseMultiple(List.of(elfPath)).get(elfPath);
    }

    /**
     * Parse multiple ELF files in one batch invocation of coderec.
     *
     * @param elfPaths the list of ELF file paths to analyze
     * @return map from each ELF Path to its list of CodeRegion
     */
    public Map<Path, List<CodeRegion>> parseMultiple(List<Path> elfPaths) {
        Map<Path, List<CodeRegion>> result = new LinkedHashMap<>();
        for (int i = 0; i < elfPaths.size(); i += BATCH_SIZE) {
            List<Path> batch = elfPaths.subList(i, Math.min(i + BATCH_SIZE, elfPaths.size()));
            String jsonAll = runCoderecBatch(batch);
            List<String> blobs = splitJsonObjects(jsonAll);
            for (String blob : blobs) {
                Map<String, Object> raw = parseRawJson(blob);
                Path key = extractPath(raw);
                List<CodeRegion> regions = extractRegions(raw);
                result.put(key, regions);
            }
        }
        return result;
    }

    /** Invokes coderec on all paths and returns the raw concatenated JSON output. */
    private String runCoderecBatch(List<Path> batch) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(coderecResource.getFile().toPath().toString());
            batch.forEach(p -> cmd.add(p.toString()));

            log.info("Running ...{}", cmd);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(batch.getFirst().getParent().toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();

            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                reader.lines().forEach(out::append);
            }
            if (p.waitFor() != 0) {
                throw new FileProcessingException("coderec failed, exit code " + p.exitValue(), null);
            }
            return out.toString();
        } catch (Exception e) {
            throw new FileProcessingException("coderec parse failure: " + e.getMessage(), e);
        }
    }

    /** Splits concatenated JSON objects by inserting a delimiter between "}{". */
    private List<String> splitJsonObjects(String all) {
        return Arrays.stream(all.replace("}{", "}@@{").split("@@"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** Deserialize a single JSON blob into a raw Map. */
    private Map<String, Object> parseRawJson(String json) {
        try {
            return MAPPER.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new FileProcessingException("Error reading json", null);
        }
    }

    /** Extract the file Path from the raw JSON map’s "file" field. */
    private Path extractPath(Map<String,Object> raw) {
        String fileField = ((String) raw.get("file")).replace("\\\\", "\\");
        return Paths.get(fileField).getFileName();
    }

    /** Convert raw JSON map into a List<CodeRegion>. */
    @SuppressWarnings("unchecked")
    private List<CodeRegion> extractRegions(Map<String,Object> raw) {
        List<List<Object>> rr = (List<List<Object>>) raw.get("range_results");
        if (rr == null) throw new FileProcessingException("missing range_results", null);

        return rr.stream().map(entry -> {
            var coords = (Map<String,Object>) entry.getFirst();
            return new CodeRegion(
                    ((Number) coords.get("start")).longValue(),
                    ((Number) coords.get("end")).longValue(),
                    ((Number) entry.get(1)).longValue(), (String) entry.get(2)
            );
        }).toList();
    }

    /**
     * Represents a region in the binary that coderec classified,
     * with start, end, byte-length, and the tag
     */
    public record CodeRegion(long start, long end, long length, String tag) {}
}
