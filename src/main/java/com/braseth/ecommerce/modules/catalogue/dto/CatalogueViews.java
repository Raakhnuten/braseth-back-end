package com.braseth.ecommerce.modules.catalogue.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Public catalogue bodies, shaped like the web app's models
 * (src/app/core/models/product.model.ts) field for field.
 *
 * No price field exists here on purpose: the public site never shows one.
 * Lead time, fabric weight, care instructions and the like come from what
 * staff wrote in the product editor; a value nobody has stated is sent
 * as null, which the web app renders as "not configured" instead of guessing.
 */
public final class CatalogueViews {

    private CatalogueViews() {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Category(String id, String slug, String name, String description, int productCount, String parentId) {
    }

    public record Media(String id, String url, String alt) {
    }

    public record Color(String id, String name, String hex) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Size(String id, String label, String measurements) {
    }

    public record Variant(String id, String sku, String colorId, String sizeId, boolean available) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Customization(
            List<String> printMethods,
            List<String> printPositions,
            boolean embroideryAvailable,
            Integer minimumOrderQuantity,
            String productionLeadTime,
            Integer maximumPrintColors) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ProductSummary(
            String id,
            String slug,
            String name,
            String shortDescription,
            String categoryId,
            String categoryName,
            Media thumbnail,
            List<Media> gallery,
            boolean customizable,
            boolean featured,
            List<Color> colors,
            String productionLeadTime) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Product(
            String id,
            String slug,
            String name,
            String shortDescription,
            String categoryId,
            String categoryName,
            Media thumbnail,
            List<Media> gallery,
            boolean customizable,
            boolean featured,
            List<Color> colors,
            String productionLeadTime,
            String description,
            String material,
            Integer weightGsm,
            List<Size> sizes,
            List<Variant> variants,
            Customization customization,
            String careInstructions,
            List<String> relatedProductIds) {
    }

    public record Page<T>(List<T> items, int page, int size, long totalItems, int totalPages) {
    }
}
