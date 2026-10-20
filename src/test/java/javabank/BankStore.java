package javabank;

/**
 * Minimal store interface for the Java bank baseline.
 * Covers exactly the operations BANK.m uses — nothing more.
 * Separate from StoreApi to avoid M engine coupling and measure pure Java overhead.
 */
public interface BankStore extends AutoCloseable {
    /** Get a node value. Returns null if not set. */
    String get(String key);

    /** Set a node value. */
    void set(String key, String value);

    /** Get numeric value (0 if not set). */
    double getNum(String key);

    /** Set numeric value. */
    void setNum(String key, double value);

    /** Atomically increment and return new value. */
    double increment(String key);

    /** Delete a node and all descendants (prefix match on key + SEP). */
    void kill(String prefix);

    /** Next key after {@code after} with given prefix, or null. */
    String order(String prefix, String after);

    /** Close resources. Default no-op (mem store). */
    @Override
    default void close() throws Exception {}
}
