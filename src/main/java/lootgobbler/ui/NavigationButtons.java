package lootgobbler.ui;

/** Turns on the mouse's back and forward buttons for the views (see Navigation). */
public final class NavigationButtons {
    private NavigationButtons() {
    }

    public static void install() {
        Navigation.install();
    }
}
