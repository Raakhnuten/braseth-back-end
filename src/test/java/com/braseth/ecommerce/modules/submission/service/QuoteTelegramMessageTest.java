package com.braseth.ecommerce.modules.submission.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.braseth.ecommerce.modules.submission.dto.SubmissionBodies.Contact;

class QuoteTelegramMessageTest {

    private static final Contact CONTACT = new Contact("Sok Dara", "ABC Trading", "dara@example.com", "012 345 678",
            "@dara", "telegram");

    @Test
    void listsTheContactProductsAndLink() {
        String text = QuoteTelegramMessage.build("QR-2026-00012", false, CONTACT,
                List.of(new QuoteTelegramMessage.Item("Sample Polo Shirt", 1200, "embroidery")),
                "Print positions: Front, Back\n\nNavy, sizes S–XL", 2, "https://braseth.com/admin/quotes/abc");

        assertThat(text)
                .startsWith("🆕 <b>New quote request</b>\n<b>QR-2026-00012</b>")
                .contains("<b>Name:</b> Sok Dara\n")
                .contains("<b>Company:</b> ABC Trading\n")
                .contains("<b>Phone:</b> 012 345 678\n")
                .contains("<b>Telegram:</b> @dara\n")
                .contains("<b>Email:</b> dara@example.com\n")
                .contains("<b>Reply by:</b> telegram\n")
                .contains("• Sample Polo Shirt × 1,200 — embroidery\n")
                .contains("<b>Notes</b>\nPrint positions: Front, Back\n\nNavy, sizes S–XL\n")
                .contains("📎 2 artwork files")
                .endsWith("<a href=\"https://braseth.com/admin/quotes/abc\">Open in the staff area</a>");
    }

    @Test
    void leavesOutWhatTheCustomerDidNotGive() {
        Contact minimal = new Contact("Dara", null, null, "012 345 678", " ", "phone");
        String text = QuoteTelegramMessage.build("QR-2026-00013", true, minimal,
                List.of(new QuoteTelegramMessage.Item("Custom request", 0, null)), null, 0, "http://x/admin/quotes/1");

        assertThat(text)
                .startsWith("🆕 <b>New free visual request</b>")
                .doesNotContain("Company", "Email", "Telegram:", "Notes", "artwork", "×")
                .contains("• Custom request — print method: recommend one\n");
    }

    @Test
    void escapesWhatTheCustomerTyped() {
        Contact sneaky = new Contact("<b>Boss</b> & Co", null, null, "012", null, "phone");
        String text = QuoteTelegramMessage.build("QR-1", false, sneaky,
                List.of(new QuoteTelegramMessage.Item("Cap", 10, null)), "<a href=\"x\">click</a>", 0, "http://x");

        assertThat(text)
                .contains("<b>Name:</b> &lt;b&gt;Boss&lt;/b&gt; &amp; Co\n")
                .contains("&lt;a href=&quot;x&quot;&gt;click&lt;/a&gt;")
                .doesNotContain("<a href=\"x\">");
    }

    @Test
    void shortensALongNoteToStayUnderTelegramsLimit() {
        String text = QuoteTelegramMessage.build("QR-1", false, CONTACT,
                List.of(new QuoteTelegramMessage.Item("Cap", 10, null)), "x".repeat(4000), 0, "http://x");

        assertThat(text).contains("x".repeat(1500) + "…").doesNotContain("x".repeat(1501));
        assertThat(text.length()).isLessThan(4096);
    }
}
