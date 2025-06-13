package com.goerdes.correlf.components;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.goerdes.correlf.exception.FileProcessingException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Entry point for invoking the native coderec analyzer via JNI.
 * <p>
 * Delegates to {@link CoderecJni#detectFile(String)} to get the raw JSON
 * output, then parses and returns a list of {@link CodeRegion} entries.
 * </p>
 */
@Component
@RequiredArgsConstructor
public class Coderec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** JNI bridge to the coderec native library. */
    private final CoderecJni coderecJni;

    /**
     * Analyze the given ELF binary and return its detected code regions.
     *
     * @param elfPath filesystem path to the ELF file to analyze
     * @return list of {@link CodeRegion} entries extracted from coderec output
     * @throws FileProcessingException if the native call fails or the JSON cannot be parsed
     */
    public List<CodeRegion> analyze(Path elfPath) {
        String json = coderecJni.detectFile(elfPath.toString());
        if (json == null) {
            throw new FileProcessingException("coderec failed to analyze " + elfPath, null);
        }
        return extractRegions(parseRawJson(json));
    }

    /** Deserialize a single JSON blob into a raw Map. */
    private Map<String, Object> parseRawJson(String json) {
        try {
            return MAPPER.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new FileProcessingException("Error reading json", null);
        }
    }

    /** Convert the raw JSON map into a list of {@link CodeRegion} instances. */
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
     * A contiguous region in the binary classified by coderec.
     *
     * @param start  inclusive byte offset where this region begins
     * @param end    exclusive byte offset where this region ends
     * @param length total number of bytes in the region
     * @param tag    classifier tag
     */
    public record CodeRegion(long start, long end, long length, String tag) {}
}
