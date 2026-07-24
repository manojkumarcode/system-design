import java.util.Objects;

/**
 * A simplified HashMap: separate chaining, power-of-two capacity,
 * load-factor-driven resizing. Not thread-safe.
 */
public class MyHashMap<K, V> {

    static final int DEFAULT_CAPACITY = 16;
    static final float DEFAULT_LOAD_FACTOR = 0.75f;

    static class Node<K, V> {
        final int hash;
        final K key;
        V value;
        Node<K, V> next;

        Node(int hash, K key, V value, Node<K, V> next) {
            this.hash = hash;
            this.key = key;
            this.value = value;
            this.next = next;
        }
    }

    private Node<K, V>[] table;
    private int size;
    private int threshold;
    private final float loadFactor;

    public MyHashMap() {
        this(DEFAULT_CAPACITY, DEFAULT_LOAD_FACTOR);
    }

    @SuppressWarnings("unchecked")
    public MyHashMap(int initialCapacity, float loadFactor) {
        if (initialCapacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
        if (loadFactor <= 0 || Float.isNaN(loadFactor))
            throw new IllegalArgumentException("bad load factor");

        int cap = tableSizeFor(initialCapacity);
        this.loadFactor = loadFactor;
        this.table = (Node<K, V>[]) new Node[cap];
        this.threshold = (int) (cap * loadFactor);
    }

    /** Round up to the next power of two. */
    private static int tableSizeFor(int c) {
        int n = c - 1;
        n |= n >>> 1;
        n |= n >>> 2;
        n |= n >>> 4;
        n |= n >>> 8;
        n |= n >>> 16;
        return (n < 0) ? 1 : n + 1;
    }

    /**
     * Spread: XOR the high 16 bits into the low 16 so that high-bit
     * variation still affects the bucket index. Null key hashes to 0.
     */
    static int spread(Object key) {
        int h;
        return (key == null) ? 0 : (h = key.hashCode()) ^ (h >>> 16);
    }

    private int indexFor(int hash) {
        return hash & (table.length - 1);   // cheap modulo, needs power-of-two length
    }

    public V put(K key, V value) {
        int hash = spread(key);
        int i = indexFor(hash);

        for (Node<K, V> e = table[i]; e != null; e = e.next) {
            if (e.hash == hash && (e.key == key || Objects.equals(e.key, key))) {
                V old = e.value;
                e.value = value;          // update in place, size unchanged
                return old;
            }
        }

        table[i] = new Node<>(hash, key, value, table[i]);   // prepend
        if (++size > threshold) resize();
        return null;
    }

    public V get(Object key) {
        Node<K, V> e = getNode(key);
        return e == null ? null : e.value;
    }

    public boolean containsKey(Object key) {
        return getNode(key) != null;
    }

    private Node<K, V> getNode(Object key) {
        int hash = spread(key);
        for (Node<K, V> e = table[indexFor(hash)]; e != null; e = e.next) {
            if (e.hash == hash && (e.key == key || Objects.equals(e.key, key))) return e;
        }
        return null;
    }

    public V remove(Object key) {
        int hash = spread(key);
        int i = indexFor(hash);

        Node<K, V> prev = null;
        for (Node<K, V> e = table[i]; e != null; prev = e, e = e.next) {
            if (e.hash == hash && (e.key == key || Objects.equals(e.key, key))) {
                if (prev == null) table[i] = e.next;
                else prev.next = e.next;
                size--;
                return e.value;
            }
        }
        return null;
    }

    /** Double capacity and redistribute every entry. */
    @SuppressWarnings("unchecked")
    private void resize() {
        Node<K, V>[] old = table;
        int newCap = old.length << 1;
        if (newCap <= 0) return;                      // overflow guard

        Node<K, V>[] next = (Node<K, V>[]) new Node[newCap];
        for (Node<K, V> head : old) {
            Node<K, V> e = head;
            while (e != null) {
                Node<K, V> nextNode = e.next;         // save before rewiring
                int i = e.hash & (newCap - 1);
                e.next = next[i];
                next[i] = e;
                e = nextNode;
            }
        }
        table = next;
        threshold = (int) (newCap * loadFactor);
    }

    public int size() { return size; }

    public boolean isEmpty() { return size == 0; }

    public static void main(String[] args) {
        MyHashMap<String, Integer> map = new MyHashMap<>();
        for (int i = 0; i < 50; i++) map.put("key" + i, i);

        System.out.println("size      = " + map.size());        // 50
        System.out.println("get key7  = " + map.get("key7"));    // 7
        System.out.println("overwrite = " + map.put("key7", 700)); // 7 (old value)
        System.out.println("get key7  = " + map.get("key7"));    // 700
        System.out.println("remove    = " + map.remove("key7")); // 700
        System.out.println("get key7  = " + map.get("key7"));    // null
        System.out.println("missing   = " + map.get("nope"));    // null

        map.put(null, 42);
        System.out.println("null key  = " + map.get(null));      // 42
    }
}
