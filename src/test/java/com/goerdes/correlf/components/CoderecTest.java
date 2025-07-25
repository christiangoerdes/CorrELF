package com.goerdes.correlf.components;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Paths;
import java.util.Objects;

@SpringBootTest
public class CoderecTest {

    @Autowired
    private CoderecJni coderec;

    @Test
    public void testCoderecIntegration() throws Exception {
        System.out.println(coderec.detectFile(Paths.get(Objects.requireNonNull(getClass().getClassLoader().getResource("busybox")).toURI()).toString()));
    }
}
