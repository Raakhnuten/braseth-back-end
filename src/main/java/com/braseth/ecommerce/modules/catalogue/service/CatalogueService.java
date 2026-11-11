package com.braseth.ecommerce.modules.catalogue.service;

import static com.braseth.ecommerce.common.util.Strings.blankToNull;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.braseth.ecommerce.common.exception.ApiProblem;
import com.braseth.ecommerce.modules.catalogue.dto.CatalogueViews.Category;
import com.braseth.ecommerce.modules.catalogue.dto.CatalogueViews.Color;
import com.braseth.ecommerce.modules.catalogue.dto.CatalogueViews.Customization;
import com.braseth.ecommerce.modules.catalogue.dto.CatalogueViews.Page;
import com.braseth.ecommerce.modules.catalogue.dto.CatalogueViews.Product;
import com.braseth.ecommerce.modules.catalogue.dto.CatalogueViews.ProductSummary;
import com.braseth.ecommerce.modules.catalogue.dto.CatalogueViews.Size;
import com.braseth.ecommerce.modules.catalogue.dto.CatalogueViews.Variant;
import com.braseth.ecommerce.modules.catalogue.repository.CatalogueQueries;
import com.braseth.ecommerce.modules.catalogue.repository.CatalogueQueries.Base;
import com.braseth.ecommerce.modules.catalogue.repository.CatalogueQueries.Filter;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class CatalogueService {

    private static final int MAX_PAGE_SIZE = 48;

    private final CatalogueQueries queries;

    public Mono<List<Category>> categories() {
        return queries.categories();
    }

    public Mono<Page<ProductSummary>> list(String search, String category, boolean featured, String printMethod,
            String sort, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        // A method the shop does not offer matches nothing, rather than everything.
        Filter filter = new Filter(blankToNull(search), blankToNull(category), featured, blankToNull(printMethod),
                sort == null ? "featured" : sort, safePage, safeSize);

        return queries.page(filter)
                .flatMap(ids -> (ids.ids().isEmpty() && safePage > 1
                        ? queries.count(filter)
                        : Mono.just(ids.total()))
                        .flatMap(total -> summaries(ids.ids()).map(items -> new Page<>(items, safePage, safeSize,
                                total, (int) Math.ceil(total / (double) safeSize)))));
    }

    public Mono<Page<ProductSummary>> bestSellers(int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return queries.bestSellers(safeSize)
                .flatMap(this::summaries)
                .map(items -> new Page<>(items, 1, safeSize, items.size(), items.isEmpty() ? 0 : 1));
    }

    public Mono<Product> bySlug(String slug) {
        return queries.idBySlug(slug)
                .switchIfEmpty(Mono.error(() -> new ApiProblem(HttpStatus.NOT_FOUND, "not_found",
                        "We could not find that product.")))
                .flatMap(this::detail);
    }

    public Mono<List<ProductSummary>> related(String slug) {
        return queries.idBySlug(slug)
                .switchIfEmpty(Mono.error(() -> new ApiProblem(HttpStatus.NOT_FOUND, "not_found",
                        "We could not find that product.")))
                .flatMap(id -> queries.related(id, 8))
                .flatMap(this::summaries);
    }

    /** Summaries for the given ids, in the given order. */
    private Mono<List<ProductSummary>> summaries(List<UUID> ids) {
        if (ids.isEmpty()) {
            return Mono.just(List.of());
        }
        return queries.bases(ids)
                .map(bases -> ids.stream()
                        .map(bases::get)
                        .filter(Objects::nonNull)
                        .map(CatalogueService::summary)
                        .toList());
    }

    private Mono<Product> detail(UUID id) {
        return Mono.zip(queries.bases(List.of(id)), queries.related(id, 8))
                .map(parts -> {
                    Base base = parts.getT1().get(id);
                    Customization customization = new Customization(
                            base.printMethods(),
                            base.printPositions(),
                            base.printMethods().contains("embroidery"),
                            base.minimumOrderQuantity(),
                            base.productionLeadTime(),
                            base.maximumPrintColors());
                    return new Product(
                            base.id().toString(),
                            base.slug(),
                            base.name(),
                            nullToEmpty(base.shortDescription()),
                            stringOf(base.categoryId()),
                            nullToEmpty(base.categoryName()),
                            base.gallery().isEmpty() ? null : base.gallery().get(0),
                            base.gallery(),
                            base.customizable(),
                            base.featured(),
                            base.colors(),
                            base.productionLeadTime(),
                            nullToEmpty(base.description()),
                            base.material(),
                            base.weightGsm(),
                            base.sizes(),
                            variants(base),
                            customization,
                            base.careInstructions(),
                            parts.getT2().stream().map(UUID::toString).toList());
                });
    }

    private static ProductSummary summary(Base base) {
        return new ProductSummary(
                base.id().toString(),
                base.slug(),
                base.name(),
                nullToEmpty(base.shortDescription()),
                stringOf(base.categoryId()),
                nullToEmpty(base.categoryName()),
                base.gallery().isEmpty() ? null : base.gallery().get(0),
                base.gallery(),
                base.customizable(),
                base.featured(),
                base.colors(),
                base.productionLeadTime());
    }

    /**
     * One variant per colour and size the product is offered in; with only
     * colours (or only sizes) the other side is empty. "Available" means
     * offered; stock is an internal matter. The id is derived from the
     * product, colour and size, so it stays the same between requests.
     */
    private static List<Variant> variants(Base base) {
        List<Color> colors = base.colors().isEmpty() ? Collections.singletonList(null) : base.colors();
        List<Size> sizes = base.sizes().isEmpty() ? Collections.singletonList(null) : base.sizes();
        List<Variant> out = new ArrayList<>();
        if (base.colors().isEmpty() && base.sizes().isEmpty()) {
            return out;
        }
        for (Color color : colors) {
            for (Size size : sizes) {
                String colorId = color == null ? null : color.id();
                String sizeId = size == null ? null : size.id();
                String key = base.id() + "/" + colorId + "/" + sizeId;
                out.add(new Variant(
                        UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString(),
                        sku(base.sku(), color, size),
                        colorId,
                        sizeId,
                        true));
            }
        }
        return out;
    }

    /** The product's SKU with the colour's first letters and the size, e.g. SAMPLE-TS-WHI-M. */
    private static String sku(String productSku, Color color, Size size) {
        if (productSku == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(productSku);
        if (color != null) {
            String name = color.name().replaceAll("[^A-Za-z0-9]", "").toUpperCase();
            out.append('-').append(name, 0, Math.min(3, name.length()));
        }
        if (size != null) {
            out.append('-').append(size.label().toUpperCase().replaceAll("\\s+", ""));
        }
        return out.toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String stringOf(UUID id) {
        return id == null ? null : id.toString();
    }
}
