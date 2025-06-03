package com.goerdes.correlf;

import com.goerdes.correlf.components.ElfHandler;
import com.goerdes.correlf.components.MinHashProvider;
import com.goerdes.correlf.model.ElfWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;

import static com.goerdes.correlf.TestUtils.getMockFile;

@SpringBootTest
public class ElfHandlerTest {

    @Test
    void testEntityCreation() throws IOException {
        System.out.println(new ElfHandler(new MinHashProvider()).createEntity(new ElfWrapper(getMockFile("busybox"))));
    }

}
