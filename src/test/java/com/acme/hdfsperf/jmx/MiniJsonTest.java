package com.acme.hdfsperf.jmx;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MiniJsonTest {
    @Test void parsesJmxShape() {
        Object value = MiniJson.parse("{\"beans\":[{\"name\":\"Rpc\",\"Count\":12,\"Avg\":1.5}],\"ok\":true}");
        assertTrue(value instanceof Map);
        Object beans = ((Map<?, ?>) value).get("beans");
        assertEquals(1, ((List<?>) beans).size());
        assertEquals(12L, ((Map<?, ?>) ((List<?>) beans).get(0)).get("Count"));
    }

    @Test void parsesEscapesAndNull() {
        Map<?, ?> value = (Map<?, ?>) MiniJson.parse("{\"s\":\"a\\n\\u4e2d\",\"n\":null}");
        assertEquals("a\n中", value.get("s"));
        assertNull(value.get("n"));
    }
}
