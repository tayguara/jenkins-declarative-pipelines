package support

/** Loads report fixtures from shared-library/test/resources/fixtures. */
class Fixtures {

    static String text(String name) {
        InputStream stream = Fixtures.class.getResourceAsStream("/fixtures/${name}")
        if (stream == null) {
            throw new IllegalArgumentException("No fixture named ${name}".toString())
        }
        return stream.getText('UTF-8')
    }
}
