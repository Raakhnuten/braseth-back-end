package com.braseth.ecommerce.modules.catalogue.repository;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Component;

import com.braseth.ecommerce.common.util.JsonNodes;
import com.braseth.ecommerce.modules.catalogue.dto.CatalogueViews.Category;
import com.braseth.ecommerce.modules.catalogue.dto.CatalogueViews.Color;
import com.braseth.ecommerce.modules.catalogue.dto.CatalogueViews.Media;
import com.braseth.ecommerce.modules.catalogue.dto.CatalogueViews.Size;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.r2dbc.spi.Readable;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/**
 * Read-only SQL over the catalogue tables. A product is on the public site
 * when it is active, not deleted, and its category (if any) is active and not
 * deleted too. A product's photos, colours, sizes, print methods and
 * positions are columns of its own row.
 */
@Component
@RequiredArgsConstructor
public class CatalogueQueries {

    /** The visibility rule, for a products table aliased {@code p}. */
    static final String VISIBLE = """
            p.is_active AND p.deleted_at IS NULL
            AND (p.category_id IS NULL OR EXISTS (
                SELECT 1 FROM categories vc
                WHERE vc.id = p.category_id AND vc.is_active AND vc.deleted_at IS NULL))
            """;

    private final DatabaseClient db;
    private final ObjectMapper json;

    /** {@code printMethod} is the web app's name for the method (silkscreen, embroidery, ...). */
    public record Filter(String search, String categorySlug, boolean featuredOnly, String printMethod, String sort,
            int page, int size) {
    }

    public record IdPage(List<UUID> ids, long total) {
    }

    /**
     * Everything the public site shows of one product. Material, care, lead
     * time, weight, minimum order and colour limit are what staff wrote in the
     * product editor; one nobody has stated is null.
     */
    public record Base(UUID id, String slug, String sku, String name, String shortDescription, String description,
            UUID categoryId, String categoryName, boolean customizable, boolean featured, String material,
            String careInstructions, String productionLeadTime, Integer weightGsm, Integer minimumOrderQuantity,
            Integer maximumPrintColors, List<Media> gallery, List<Color> colors, List<Size> sizes,
            List<String> printMethods, List<String> printPositions) {
    }

    public Mono<List<Category>> categories() {
        return db.sql("""
                SELECT c.id, c.slug, c.name, c.description, c.parent_id,
                       (SELECT count(*) FROM products p WHERE p.category_id = c.id AND %s) AS product_count
                FROM categories c
                WHERE c.is_active AND c.deleted_at IS NULL
                ORDER BY c.sort_order, c.name
                """.formatted(VISIBLE))
                .map(row -> new Category(
                        row.get("id", UUID.class).toString(),
                        row.get("slug", String.class),
                        row.get("name", String.class),
                        row.get("description", String.class),
                        row.get("product_count", Long.class).intValue(),
                        stringOf(row.get("parent_id", UUID.class))))
                .all()
                .collectList();
    }

    /** One page of visible product ids, in the requested order, plus the total. */
    public Mono<IdPage> page(Filter filter) {
        String order = switch (filter.sort()) {
            case "name-asc" -> "p.name ASC, p.id";
            case "name-desc" -> "p.name DESC, p.id";
            case "newest" -> "p.created_at DESC, p.id";
            default -> "p.featured DESC, p.name ASC, p.id";
        };
        GenericExecuteSpec spec = db.sql("""
                WITH RECURSIVE branch AS (
                    SELECT id FROM categories
                    WHERE slug = CAST(:category AS text) AND is_active AND deleted_at IS NULL
                    UNION ALL
                    SELECT c.id FROM categories c JOIN branch b ON c.parent_id = b.id
                    WHERE c.is_active AND c.deleted_at IS NULL
                )
                SELECT p.id, count(*) OVER () AS total
                FROM products p
                LEFT JOIN categories c ON c.id = p.category_id
                WHERE %s
                  AND (CAST(:category AS text) IS NULL OR p.category_id IN (SELECT id FROM branch))
                  AND (NOT :featured OR p.featured)
                  AND (CAST(:search AS text) IS NULL
                       OR strpos(lower(p.name), lower(:search)) > 0
                       OR strpos(lower(coalesce(c.name, '')), lower(:search)) > 0
                       OR strpos(replace(p.slug, '-', ' '), lower(:search)) > 0)
                  AND (CAST(:method AS text) IS NULL OR CAST(:method AS text) = ANY(p.print_methods))
                ORDER BY %s
                LIMIT :limit OFFSET :offset
                """.formatted(VISIBLE, order));
        spec = bindText(spec, "category", filter.categorySlug());
        spec = bindText(spec, "search", filter.search());
        spec = bindText(spec, "method", filter.printMethod());
        return spec
                .bind("featured", filter.featuredOnly())
                .bind("limit", filter.size())
                .bind("offset", (filter.page() - 1) * filter.size())
                .map(row -> new Object[] { row.get("id", UUID.class), row.get("total", Long.class) })
                .all()
                .collectList()
                .map(rows -> new IdPage(
                        rows.stream().map(r -> (UUID) r[0]).toList(),
                        rows.isEmpty() ? 0 : (Long) rows.get(0)[1]));
    }

    /**
     * When a page is past the end, the window count above has no row to ride
     * on; this gives the true total so the pager still knows how many pages
     * there are.
     */
    public Mono<Long> count(Filter filter) {
        return page(new Filter(filter.search(), filter.categorySlug(), filter.featuredOnly(),
                filter.printMethod(), filter.sort(), 1, 1)).map(IdPage::total);
    }

    public Mono<UUID> idBySlug(String slug) {
        return db.sql("SELECT p.id FROM products p WHERE p.slug = :slug AND " + VISIBLE)
                .bind("slug", slug)
                .map(row -> row.get("id", UUID.class))
                .one();
    }

    /**
     * Visible products ranked by units on real orders (the catalogue products
     * on each accepted quote), after any staff have pinned as best sellers;
     * empty until there are orders or pins.
     */
    public Mono<List<UUID>> bestSellers(int limit) {
        return db.sql("""
                SELECT p.id, p.best_seller, coalesce(sum(u.units), 0) AS units
                FROM products p
                LEFT JOIN (
                    SELECT CAST(l ->> 'productId' AS uuid) AS product_id, CAST(l ->> 'quantity' AS integer) AS units
                    FROM orders o JOIN quote_requests q ON q.id = o.quote_request_id
                    CROSS JOIN jsonb_array_elements(q.lines) l
                    WHERE o.status <> 'cancelled' AND l ->> 'productId' IS NOT NULL
                ) u ON u.product_id = p.id
                WHERE %s
                GROUP BY p.id
                HAVING p.best_seller OR sum(u.units) > 0
                ORDER BY p.best_seller DESC, units DESC, p.id
                LIMIT :limit
                """.formatted(VISIBLE))
                .bind("limit", limit)
                .map(row -> row.get("id", UUID.class))
                .all()
                .collectList();
    }

    /** Other visible products: same category first, then featured, then by name. */
    public Mono<List<UUID>> related(UUID productId, int limit) {
        return db.sql("""
                SELECT p.id FROM products p, products me
                WHERE me.id = :id AND p.id <> me.id AND %s
                ORDER BY (p.category_id IS NOT DISTINCT FROM me.category_id) DESC, p.featured DESC, p.name
                LIMIT :limit
                """.formatted(VISIBLE))
                .bind("id", productId)
                .bind("limit", limit)
                .map(row -> row.get("id", UUID.class))
                .all()
                .collectList();
    }

    public Mono<Map<UUID, Base>> bases(List<UUID> ids) {
        return db.sql("""
                SELECT p.id, p.slug, p.sku, p.name, p.short_description, p.description, p.category_id,
                       c.name AS category_name, p.is_customizable, p.featured,
                       nullif(p.web_text #>> '{en,material}', '') AS material,
                       nullif(p.web_text #>> '{en,careInstructions}', '') AS care,
                       nullif(p.web_text #>> '{en,productionLeadTime}', '') AS lead_time,
                       p.weight_gsm, p.minimum_order_quantity, p.maximum_print_colors,
                       p.photos, p.colors, p.sizes, p.print_methods, p.print_positions
                FROM products p LEFT JOIN categories c ON c.id = p.category_id
                WHERE p.id = ANY(:ids)
                """)
                .bind("ids", ids.toArray(UUID[]::new))
                .map(this::base)
                .all()
                .collectMap(Base::id);
    }

    private Base base(Readable row) {
        String name = row.get("name", String.class);
        return new Base(
                row.get("id", UUID.class),
                row.get("slug", String.class),
                row.get("sku", String.class),
                name,
                row.get("short_description", String.class),
                row.get("description", String.class),
                row.get("category_id", UUID.class),
                row.get("category_name", String.class),
                Boolean.TRUE.equals(row.get("is_customizable", Boolean.class)),
                Boolean.TRUE.equals(row.get("featured", Boolean.class)),
                row.get("material", String.class),
                row.get("care", String.class),
                row.get("lead_time", String.class),
                row.get("weight_gsm", Integer.class),
                row.get("minimum_order_quantity", Integer.class),
                row.get("maximum_print_colors", Integer.class),
                gallery(JsonNodes.column(json, row, "photos"), name),
                colors(JsonNodes.column(json, row, "colors")),
                sizes(JsonNodes.column(json, row, "sizes")),
                texts(row.get("print_methods", String[].class)),
                texts(row.get("print_positions", String[].class)));
    }

    /** In stored order; the first is the main photo. Alt text falls back to the product name. */
    private static List<Media> gallery(JsonNode photos, String productName) {
        List<Media> out = new ArrayList<>();
        for (JsonNode photo : photos) {
            String alt = photo.path("alt").asText("");
            out.add(new Media(photo.path("id").asText(), photo.path("url").asText(), alt.isBlank() ? productName : alt));
        }
        return out;
    }

    private static List<Color> colors(JsonNode colors) {
        List<Color> out = new ArrayList<>();
        for (JsonNode color : colors) {
            out.add(new Color(color.path("id").asText(), color.path("name").asText(), color.path("hex").asText()));
        }
        return out;
    }

    /** Measurements are what staff wrote for this product, when they did. */
    private static List<Size> sizes(JsonNode sizes) {
        List<Size> out = new ArrayList<>();
        for (JsonNode size : sizes) {
            String measurements = size.path("measurements").asText(null);
            out.add(new Size(size.path("id").asText(), size.path("label").asText(),
                    measurements == null || measurements.isBlank() ? null : measurements));
        }
        return out;
    }

    private static List<String> texts(String[] values) {
        return values == null ? List.of() : Arrays.asList(values);
    }

    private static GenericExecuteSpec bindText(GenericExecuteSpec spec, String name, String value) {
        return value == null ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }

    private static String stringOf(UUID id) {
        return id == null ? null : id.toString();
    }
}
