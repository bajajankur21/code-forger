package com.codeforger;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "APP_SECRET_PASSCODE=test-secret")
class CodeForgerApplicationTests {

    @Test
    void contextLoads() {
    }
}
