package support

/**
 * Tiny assertion helper: runs a closure and returns the exception it threw,
 * failing the test when nothing was thrown. Keeps the tests free of Java lambdas,
 * which Groovy 2.4 does not support.
 */
class Thrown {

    static Throwable by(Closure body) {
        try {
            body.call()
        } catch (Throwable t) {
            return t
        }
        throw new AssertionError((Object) 'Expected an exception, but none was thrown')
    }

    static <T extends Throwable> T by(Class<T> type, Closure body) {
        Throwable t = by(body)
        if (!type.isInstance(t)) {
            throw new AssertionError((Object) "Expected ${type.simpleName} but got ${t.class.simpleName}: ${t.message}".toString())
        }
        return (T) t
    }
}
