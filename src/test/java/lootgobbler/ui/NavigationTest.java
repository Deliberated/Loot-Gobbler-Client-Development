package lootgobbler.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NavigationTest {
    @Test
    void backAndForwardLikeABrowser() {
        List<String> shown = new ArrayList<>();
        for (String v : new String[]{"a", "b", "b", "c"}) Navigation.visit(v, () -> {
            shown.add(v);
            Navigation.visit(v + "-again", () -> { });   // showing a view again records nothing
        });
        Navigation.back();
        Navigation.back();
        Navigation.back();   // nothing before a: stays
        Navigation.forward();
        assertEquals(List.of("b", "a", "b"), shown);
        Navigation.visit("d", () -> shown.add("d"));   // something new drops what was ahead (c)
        Navigation.forward();
        Navigation.back();
        assertEquals(List.of("b", "a", "b", "b"), shown);
    }
}
