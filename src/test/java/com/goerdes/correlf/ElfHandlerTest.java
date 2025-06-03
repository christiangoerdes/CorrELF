package com.goerdes.correlf;

import com.goerdes.correlf.components.ElfHandler;
import com.goerdes.correlf.components.MinHashProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;

import static com.goerdes.correlf.TestUtils.getMockFile;
import static com.goerdes.correlf.components.ElfHandler.fromMultipart;

@SpringBootTest
public class ElfHandlerTest {

    @Test
    void testEntityCreation() throws IOException {
        System.out.println(new ElfHandler(new MinHashProvider()).createEntity(fromMultipart(getMockFile("busybox"))));
    }

}
