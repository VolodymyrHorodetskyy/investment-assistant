package com.investmentassistant;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "app.database.path=${java.io.tmpdir}/investment-assistant/context-test.db")
class InvestmentAssistantApplicationTests {

    @Test
    void contextStarts() {
    }
}
