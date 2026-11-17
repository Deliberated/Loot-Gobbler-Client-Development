package util;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Properties;

/**
 * Properties class saving options to file and loads options when restarting.
 */
public class PropertiesManager {

    private static Properties properties;

    /*
     * Load the properties as the class loads.
     */
    static {
        properties = new Properties();
        try {
            FileReader reader = new FileReader("realmShark.properties");
            properties.load(reader);
            reader.close();
        } catch (IOException ignored) {
        }
    }

    /**
     * Sets a preset needed when reloading the program.
     *
     * @param name  Name of the property.
     * @param value Value of the property.
     */
    public static synchronized void setProperties(String name, String value) {
        // [Loot Gobbler patch] settings that could not be read at start (the file was missing or being written then)
        // but are there now are merged in first: saving would otherwise replace them all with this one value
        java.io.File file = new java.io.File("realmShark.properties");
        if (file.length() > 0) {
            Properties onDisk = new Properties();
            try (FileReader reader = new FileReader(file)) {
                onDisk.load(reader);
                for (String k : onDisk.stringPropertyNames()) properties.putIfAbsent(k, onDisk.getProperty(k));
            } catch (IOException ignored) {
            }
        }
        properties.setProperty(name, value);
        // written to a temporary file and moved over the old one: a reader never sees it empty or half written
        java.io.File tmp = new java.io.File("realmShark.properties.tmp");
        try (FileWriter writer = new FileWriter(tmp)) {
            properties.store(writer, "Realm shark properties");
        } catch (IOException e) {
            return;
        }
        try {
            java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) {
        }
    }

    /**
     * Gets the property value by the name of the property.
     *
     * @param name Name of the property
     * @return Value of the property.
     */
    public static String getProperty(String name) {
        if (properties == null) return null;
        return properties.getProperty(name);
    }
}
