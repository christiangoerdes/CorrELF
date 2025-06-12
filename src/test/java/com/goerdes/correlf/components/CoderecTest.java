package com.goerdes.correlf.components;

import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class CoderecTest {

    @Test
    public void testCoderecIntegration() throws Exception {

        String jsonResult = Coderec.detectFile(Paths.get(Objects.requireNonNull(getClass().getClassLoader().getResource("busybox")).toURI()).toString());
        System.out.println(jsonResult);

        assertNotNull(jsonResult);
        assertFalse(jsonResult.isEmpty());
    }
}
