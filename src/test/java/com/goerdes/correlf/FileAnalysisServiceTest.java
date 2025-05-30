package com.goerdes.correlf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.goerdes.correlf.services.FileAnalysisService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static com.goerdes.correlf.TestUtils.getMockFile;

@SpringBootTest
public class FileAnalysisServiceTest {

    @Autowired
    private FileAnalysisService fileAnalysisService;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void testAnalyzeBinaries() throws Exception {

        for (String file : new String[]{
                "busybox",
                "dropbear",
                "busybox_arm",
                "busybox_x86"
        }) {

            System.out.println("=== Analysis results for '" + file + "' ===");
            System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(fileAnalysisService.analyze(getMockFile(file))));
            System.out.println();
        }
    }
}
