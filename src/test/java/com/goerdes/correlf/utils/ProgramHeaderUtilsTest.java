package com.goerdes.correlf.utils;

import com.goerdes.correlf.model.ProgramHeader;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.List;

import static com.goerdes.correlf.utils.ProgramHeaderUtils.extractProgramHeaders;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ProgramHeaderUtilsTest {

    @Test
    void test() throws Exception {
        List<ProgramHeader> programHeaders = extractProgramHeaders(Paths.get("src/test/resources/busybox"));
        for (ProgramHeader info : programHeaders) {
            System.out.println("info = " + info);
        }
        assertEquals(10, programHeaders.size());
    }

}