package com.braseth.ecommerce.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

class PagingTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void buildsTheWebAppsPagedShape() {
        ObjectNode page = Paging.page(json, List.of(TextNode.valueOf("a"), TextNode.valueOf("b")), 2, 20, 45);

        assertThat(page.get("items")).hasSize(2);
        assertThat(page.get("page").asInt()).isEqualTo(2);
        assertThat(page.get("size").asInt()).isEqualTo(20);
        assertThat(page.get("totalItems").asLong()).isEqualTo(45);
        assertThat(page.get("totalPages").asInt()).isEqualTo(3);
    }

    @Test
    void emptyResultHasNoPages() {
        ObjectNode page = Paging.page(json, List.of(), 1, 20, 0);

        assertThat(page.get("items")).isEmpty();
        assertThat(page.get("totalPages").asInt()).isZero();
    }
}
