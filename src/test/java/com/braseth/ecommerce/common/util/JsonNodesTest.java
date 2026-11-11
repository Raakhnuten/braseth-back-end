package com.braseth.ecommerce.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class JsonNodesTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void parsesJsonText() {
        assertThat(JsonNodes.parse(json, "{\"total\":5}").path("total").asInt()).isEqualTo(5);
    }

    @Test
    void parseRejectsBrokenJson() {
        assertThatThrownBy(() -> JsonNodes.parse(json, "{broken"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void parseOrNullToleratesMissingAndBrokenJson() {
        assertThat(JsonNodes.parseOrNull(json, null).isNull()).isTrue();
        assertThat(JsonNodes.parseOrNull(json, "{broken").isNull()).isTrue();
        assertThat(JsonNodes.parseOrNull(json, "[1,2]")).hasSize(2);
    }
}
