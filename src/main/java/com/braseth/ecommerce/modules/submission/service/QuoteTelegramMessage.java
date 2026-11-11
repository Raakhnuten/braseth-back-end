package com.braseth.ecommerce.modules.submission.service;

import static com.braseth.ecommerce.infrastructure.telegram.TelegramNotifier.escape;

import java.util.List;

import com.braseth.ecommerce.modules.submission.dto.SubmissionBodies.Contact;

/**
 * The Telegram alert for a new quote request: who asked, how to reach them,
 * what they want, and a link to the request in the staff area. Everything
 * the customer typed is escaped, because the message is sent as HTML.
 */
final class QuoteTelegramMessage {

    /** Telegram caps a message at 4,096 characters; the note is the only long part. */
    private static final int MAX_NOTE = 1500;

    private QuoteTelegramMessage() {
    }

    /** One requested product, named as it was when the request came in. */
    record Item(String productName, int quantity, String printMethod) {
    }

    static String build(String reference, boolean freeVisual, Contact contact, List<Item> items, String note,
            int artworkFiles, String adminUrl) {
        StringBuilder text = new StringBuilder();
        text.append("🆕 <b>").append(freeVisual ? "New free visual request" : "New quote request").append("</b>\n")
                .append("<b>").append(escape(reference)).append("</b>\n\n");

        line(text, "Name", contact.fullName());
        line(text, "Company", contact.companyName());
        line(text, "Phone", contact.phone());
        line(text, "Telegram", contact.telegram());
        line(text, "Email", contact.email());
        line(text, "Reply by", contact.preferredContactMethod());

        text.append("\n<b>Products</b>\n");
        for (Item item : items) {
            text.append("• ").append(escape(item.productName()));
            if (item.quantity() > 0) {
                text.append(" × ").append(String.format("%,d", item.quantity()));
            }
            text.append(" — ").append(item.printMethod() == null ? "print method: recommend one" : escape(item.printMethod()))
                    .append('\n');
        }

        if (note != null && !note.isBlank()) {
            String trimmed = note.strip();
            if (trimmed.length() > MAX_NOTE) {
                trimmed = trimmed.substring(0, MAX_NOTE) + "…";
            }
            text.append("\n<b>Notes</b>\n").append(escape(trimmed)).append('\n');
        }
        if (artworkFiles > 0) {
            text.append("\n📎 ").append(artworkFiles).append(artworkFiles == 1 ? " artwork file" : " artwork files")
                    .append('\n');
        }
        text.append("\n<a href=\"").append(escape(adminUrl)).append("\">Open in the staff area</a>");
        return text.toString();
    }

    private static void line(StringBuilder text, String label, String value) {
        if (value != null && !value.isBlank()) {
            text.append("<b>").append(label).append(":</b> ").append(escape(value.strip())).append('\n');
        }
    }
}
