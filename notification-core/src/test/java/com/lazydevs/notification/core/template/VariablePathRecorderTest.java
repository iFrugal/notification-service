package com.lazydevs.notification.core.template;

import freemarker.template.SimpleScalar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VariablePathRecorderTest {

    @Test
    void parse_splitsRootKeysAndIndexes() {
        assertThat(VariablePathRecorder.parse(" order.items[2][\"unit price\"]['a\\'b'].sku "))
                .containsExactly("order", "items", 2, "unit price", "a'b", "sku");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"(a + b)", "a.b()", "a?upper_case", "a[b]", "a.", "1abc", "a[-1]",
            "a[", "a[]", "a[1", "a[1234567890]", "a[\"b", "a[\"b\"", "a[\"b\"x", "a['b\\", "a[\"b\\\nc\"]",
            "a['b\\\u2028c']"})
    void parse_rejectsAnythingButAPlainPath(String expression) {
        assertThat(VariablePathRecorder.parse(expression)).isEmpty();
    }

    @Test
    void parse_keepsEscapesAndTheOtherQuoteInsideAKey() {
        assertThat(VariablePathRecorder.parse("a[\"x\\\"y'z\\\\\"]['q\"r'][123456789]"))
                .containsExactly("a", "x\"y'z\\", "q\"r", 123456789);
    }

    @Test
    void parse_handlesVeryLongExpressionsWithoutStackOverflow() {
        String longKey = "k\\'".repeat(35_000);
        assertThat(VariablePathRecorder.parse("a['" + longKey + "']"))
                .containsExactly("a", "k'".repeat(35_000));

        String longPath = "a" + ".b[0]".repeat(20_000);
        assertThat(VariablePathRecorder.parse(longPath)).hasSize(40_001);

        assertThat(VariablePathRecorder.parse("a[\"" + "x".repeat(100_000))).isEmpty();
    }

    @Test
    void find_returnsTheDataModelPathByIdentity() {
        Map<String, Object> address = new HashMap<>();
        List<Object> items = List.of(new HashMap<>(Map.of("sku", "S1")), new HashMap<>(Map.of("sku", "S2")));
        Object[] tags = {new HashMap<>()};
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("home address", address);
        user.put("items", items);
        user.put("tags", tags);
        Map<String, Object> model = new HashMap<>();
        model.put("user", user);
        model.put("my-key", address);
        model.put("helper", new SimpleScalar("a template model, not data"));

        assertThat(VariablePathRecorder.find(Map.of("user", user), address)).isEqualTo("user[\"home address\"]");
        assertThat(VariablePathRecorder.find(model, items.get(1))).isEqualTo("user.items[1]");
        assertThat(VariablePathRecorder.find(model, tags[0])).isEqualTo("user.tags[0]");
        assertThat(VariablePathRecorder.find(Map.of("my-key", address), address)).isEqualTo("[\"my-key\"]");
        assertThat(VariablePathRecorder.find(model, new HashMap<>())).isNull();
    }

    @Test
    void find_ignoresValuesWithoutIdentity() {
        String name = "Ada";
        Map<String, Object> model = Map.of("name", name, "count", 3, "flag", true, "day", LocalDate.EPOCH,
                "letter", 'x');

        assertThat(VariablePathRecorder.find(model, name)).isNull();
        assertThat(VariablePathRecorder.find(model, 3)).isNull();
        assertThat(VariablePathRecorder.find(model, null)).isNull();
    }

    @Test
    void find_survivesCyclesAndDeepModels() {
        Map<String, Object> cyclic = new HashMap<>();
        cyclic.put("self", cyclic);
        Map<String, Object> deep = new HashMap<>();
        Map<String, Object> cursor = deep;
        for (int i = 0; i < 40; i++) {
            Map<String, Object> next = new HashMap<>();
            cursor.put("n", next);
            cursor = next;
        }
        List<Object> list = new ArrayList<>();
        list.add(list);

        assertThat(VariablePathRecorder.find(Map.of("c", cyclic, "d", deep, "l", list), cursor)).isNull();
        assertThat(VariablePathRecorder.find(Map.of("c", cyclic), cyclic)).isEqualTo("c");
    }
}
