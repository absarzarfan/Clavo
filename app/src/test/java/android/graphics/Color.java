package android.graphics;

/**
 * JVM unit-test stand-in for android.graphics.Color. The real class is a stub in the unit-test
 * android.jar (it throws "not mocked"); Robolectric 4.4 cannot run on JDK 17. Test sources come
 * first on the classpath, so this class is used instead. Only what ClassColors needs.
 */
public class Color {
    public static int parseColor(String colorString) {
        if (colorString == null || !colorString.startsWith("#")) {
            throw new IllegalArgumentException("Unknown color: " + colorString);
        }
        long v = Long.parseLong(colorString.substring(1), 16);
        if (colorString.length() == 7) v |= 0xFF000000L; // #RRGGBB -> opaque ARGB
        return (int) v;
    }
}
