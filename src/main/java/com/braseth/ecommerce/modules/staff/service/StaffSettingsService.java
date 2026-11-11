package com.braseth.ecommerce.modules.staff.service;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

import com.braseth.ecommerce.common.exception.ApiProblem;
import com.braseth.ecommerce.common.util.JsonNodes;
import com.braseth.ecommerce.modules.staff.service.StaffGate.Staff;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import io.r2dbc.postgresql.codec.Json;
import io.r2dbc.spi.Readable;
import reactor.core.publisher.Mono;

/**
 * The site settings document (AdminSiteSettings), one section per form.
 * The public /site-config is read from the same row, so a save shows on the
 * site at once. Blank text is stored as null, which the site hides.
 */
@Service
public class StaffSettingsService {

    /** The only map address the Contact page will put in an iframe (core/utils/map-embed.ts). */
    private static final Pattern MAP_EMBED = Pattern.compile("^https://www\\.google\\.com/maps/embed\\?.*");

    private static final Pattern TIME = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");

    private static final Set<String> PLATFORMS = Set.of("facebook", "telegram", "instagram", "tiktok", "linkedin", "youtube");

    private static final List<String> ABOUT = List.of("intro", "story", "mission", "vision", "capability", "quality");

    private final DatabaseClient db;
    private final ObjectMapper json;
    private final StaffRecords records;
    private final TransactionalOperator transaction;

    StaffSettingsService(DatabaseClient db, ObjectMapper json, StaffRecords records,
            ReactiveTransactionManager transactionManager) {
        this.db = db;
        this.json = json;
        this.records = records;
        this.transaction = TransactionalOperator.create(transactionManager);
    }

    public Mono<ObjectNode> load() {
        return db.sql("SELECT s.*, u.name AS updated_by_name FROM site_settings s LEFT JOIN users u ON u.id = s.updated_by WHERE s.id = 1")
                .map(this::view)
                .one();
    }

    public Mono<ObjectNode> save(Staff actor, String section, JsonNode body) {
        Mono<Void> write;
        try {
            write = switch (section) {
                case "contact" -> saveContact(actor, body);
                case "hours" -> saveJson(actor, "business_hours", hours(body));
                case "social" -> saveJson(actor, "social", social(body));
                case "text" -> saveText(actor, body);
                default -> throw StaffViews.notFound("settings section");
            };
        } catch (ApiProblem problem) {
            return Mono.error(problem);
        }
        String label = switch (section) {
            case "contact" -> "Updated contact details";
            case "hours" -> "Updated opening hours";
            case "social" -> "Updated social links";
            default -> "Updated the About text";
        };
        return write.then(records.activity(actor, "settings", null, "Site settings", label))
                .as(transaction::transactional)
                .then(load());
    }

    // ---- Sections -----------------------------------------------------------

    private Mono<Void> saveContact(Staff actor, JsonNode body) {
        requireObject(body);
        String company = clean(body.get("companyName"));
        if (company == null) {
            throw ApiProblem.field("companyName", "required", "Enter the company name.");
        }
        String map = clean(body.get("mapEmbedUrl"));
        if (map != null && !MAP_EMBED.matcher(map).matches()) {
            throw ApiProblem.field("mapEmbedUrl", "invalid_map",
                    "Paste the address from Google Maps → Share → Embed a map (it starts https://www.google.com/maps/embed?).");
        }
        ArrayNode phones = json.createArrayNode();
        for (JsonNode phone : arrayOf(body.get("phones"), "phones")) {
            String value = clean(phone);
            if (value != null) {
                phones.add(limit(value, 40, "phones"));
            }
        }
        JsonNode address = address(body.get("address"));
        var spec = db.sql("""
                UPDATE site_settings SET company_name = :company, legal_name = :legal, phones = :phones,
                       email = :email, telegram = :telegram, address = :address, map_embed_url = :map,
                       updated_at = now(), updated_by = :actor
                WHERE id = 1
                """)
                .bind("company", limit(company, 200, "companyName"))
                .bind("phones", Json.of(phones.toString()))
                .bind("actor", actor.id());
        spec = StaffViews.bind(spec, "legal", limitOrNull(clean(body.get("legalName")), 200, "legalName"), String.class);
        spec = StaffViews.bind(spec, "email", limitOrNull(clean(body.get("email")), 255, "email"), String.class);
        spec = StaffViews.bind(spec, "telegram", limitOrNull(clean(body.get("telegram")), 100, "telegram"), String.class);
        spec = address.isNull() ? spec.bindNull("address", Json.class) : spec.bind("address", Json.of(address.toString()));
        spec = StaffViews.bind(spec, "map", map, String.class);
        return spec.then();
    }

    private Mono<Void> saveText(Staff actor, JsonNode body) {
        requireObject(body);
        ObjectNode text = json.createObjectNode();
        for (String locale : List.of("en", "km")) {
            JsonNode words = body.path(locale);
            ObjectNode out = text.putObject(locale);
            out.set("tagline", textOrNull(words.get("tagline"), 300, locale + ".tagline"));
            ObjectNode about = out.putObject("about");
            for (String field : ABOUT) {
                about.set(field, textOrNull(words.path("about").get(field), 5000, locale + ".about." + field));
            }
        }
        return saveJson(actor, "site_text", text);
    }

    private Mono<Void> saveJson(Staff actor, String column, JsonNode value) {
        // The column name comes from the switch above, never from the request.
        return db.sql("UPDATE site_settings SET " + column + " = :value, updated_at = now(), updated_by = :actor WHERE id = 1")
                .bind("value", Json.of(value.toString()))
                .bind("actor", actor.id())
                .then();
    }

    private ArrayNode hours(JsonNode body) {
        ArrayNode out = json.createArrayNode();
        Set<Integer> days = new HashSet<>();
        for (JsonNode entry : arrayOf(body, "hours")) {
            int day = entry.path("day").asInt(-1);
            if (day < 0 || day > 6 || !days.add(day)) {
                throw ApiProblem.badRequest("invalid_hours", "Each day of the week can appear once.");
            }
            boolean closed = entry.path("closed").asBoolean(false);
            ObjectNode item = out.addObject().put("day", day).put("closed", closed);
            String opens = time(entry.get("opensAt"));
            String closes = time(entry.get("closesAt"));
            String breakStarts = time(entry.get("breakStartsAt"));
            String breakEnds = time(entry.get("breakEndsAt"));
            if (!closed && (opens == null || closes == null || opens.compareTo(closes) >= 0)) {
                throw ApiProblem.badRequest("invalid_hours", "Give an opening time before the closing time for every open day.");
            }
            if ((breakStarts == null) != (breakEnds == null)) {
                throw ApiProblem.badRequest("invalid_hours", "Give both ends of the midday break, or neither.");
            }
            if (!closed && breakStarts != null
                    && !(opens.compareTo(breakStarts) < 0 && breakStarts.compareTo(breakEnds) < 0 && breakEnds.compareTo(closes) < 0)) {
                throw ApiProblem.badRequest("invalid_hours", "The break must fall inside the opening hours.");
            }
            item.put("opensAt", closed ? null : opens);
            item.put("closesAt", closed ? null : closes);
            item.put("breakStartsAt", closed ? null : breakStarts);
            item.put("breakEndsAt", closed ? null : breakEnds);
        }
        return out;
    }

    private ArrayNode social(JsonNode body) {
        ArrayNode out = json.createArrayNode();
        for (JsonNode entry : arrayOf(body, "social")) {
            String platform = clean(entry.get("platform"));
            String url = clean(entry.get("url"));
            if (platform == null || !PLATFORMS.contains(platform)) {
                throw ApiProblem.badRequest("invalid_social", "Choose a platform for every link.");
            }
            if (url == null || !url.startsWith("https://")) {
                throw ApiProblem.badRequest("invalid_social", "Every link must be a full https:// address.");
            }
            String label = clean(entry.get("label"));
            out.addObject()
                    .put("platform", platform)
                    .put("label", label == null ? platform : limit(label, 80, "label"))
                    .put("url", limit(url, 500, "url"));
        }
        return out;
    }

    private JsonNode address(JsonNode body) {
        if (body == null || body.isNull()) {
            return NullNode.getInstance();
        }
        requireObject(body);
        String line1 = clean(body.get("line1"));
        String city = clean(body.get("city"));
        String country = clean(body.get("country"));
        if (line1 == null && city == null) {
            return NullNode.getInstance();
        }
        if (line1 == null || city == null || country == null) {
            throw ApiProblem.field("address", "incomplete_address", "Give the street, city and country, or leave the address empty.");
        }
        ObjectNode out = json.createObjectNode().put("line1", limit(line1, 200, "address"));
        putIf(out, "line2", clean(body.get("line2")));
        out.put("city", limit(city, 100, "address"));
        putIf(out, "province", clean(body.get("province")));
        putIf(out, "postalCode", clean(body.get("postalCode")));
        out.put("country", limit(country, 100, "address"));
        return out;
    }

    // ---- View ---------------------------------------------------------------

    private ObjectNode view(Readable row) {
        ObjectNode out = json.createObjectNode();
        ObjectNode contact = out.putObject("contact");
        contact.put("companyName", row.get("company_name", String.class));
        StaffViews.text(contact, "legalName", row.get("legal_name", String.class));
        contact.set("phones", JsonNodes.column(json, row, "phones"));
        StaffViews.text(contact, "email", row.get("email", String.class));
        StaffViews.text(contact, "telegram", row.get("telegram", String.class));
        contact.set("address", JsonNodes.column(json, row, "address"));
        StaffViews.text(contact, "mapEmbedUrl", row.get("map_embed_url", String.class));
        out.set("hours", JsonNodes.column(json, row, "business_hours"));
        out.set("social", JsonNodes.column(json, row, "social"));
        JsonNode stored = JsonNodes.column(json, row, "site_text");
        ObjectNode text = out.putObject("text");
        for (String locale : List.of("en", "km")) {
            JsonNode words = stored.path(locale);
            ObjectNode localeOut = text.putObject(locale);
            localeOut.set("tagline", orNull(words.get("tagline")));
            ObjectNode about = localeOut.putObject("about");
            for (String field : ABOUT) {
                about.set(field, orNull(words.path("about").get(field)));
            }
        }
        StaffViews.instant(out, "updatedAt", row.get("updated_at", OffsetDateTime.class));
        StaffViews.text(out, "updatedBy", row.get("updated_by_name", String.class));
        return out;
    }

    // ---- Helpers ------------------------------------------------------------

    private static void requireObject(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw ApiProblem.badRequest("bad_request", "The request could not be read.");
        }
    }

    private static Iterable<JsonNode> arrayOf(JsonNode body, String field) {
        if (body == null || body.isNull()) {
            return List.of();
        }
        if (!body.isArray() || body.size() > 50) {
            throw ApiProblem.field(field, "invalid", "The request could not be read.");
        }
        return body;
    }

    private static String clean(JsonNode node) {
        if (node == null || node.isNull() || !node.isValueNode()) {
            return null;
        }
        String value = node.asText().trim();
        return value.isEmpty() ? null : value;
    }

    private static String time(JsonNode node) {
        String value = clean(node);
        if (value == null) {
            return null;
        }
        if (!TIME.matcher(value).matches()) {
            throw ApiProblem.badRequest("invalid_hours", "Write times as HH:MM, e.g. 08:00.");
        }
        return value;
    }

    private static JsonNode textOrNull(JsonNode node, int max, String field) {
        String value = clean(node);
        return value == null ? NullNode.getInstance() : TextNode.valueOf(limit(value, max, field));
    }

    private static JsonNode orNull(JsonNode node) {
        return node == null || node.isNull() || node.asText().isBlank() ? NullNode.getInstance() : node;
    }

    private static String limit(String value, int max, String field) {
        if (value.length() > max) {
            throw ApiProblem.field(field, "too_long", "Keep this under " + max + " characters.");
        }
        return value;
    }

    private static String limitOrNull(String value, int max, String field) {
        return value == null ? null : limit(value, max, field);
    }

    private static void putIf(ObjectNode out, String field, String value) {
        if (value != null) {
            out.put(field, value);
        }
    }
}
