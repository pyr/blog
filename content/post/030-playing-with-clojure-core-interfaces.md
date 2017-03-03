#+title: Playing with Clojure core interfaces
#+date: 2014-11-06

One of [Alan Perlis](http://en.wikipedia.org/wiki/Alan_Perlis)' famous
quote is

> It is better to have 100 functions operate on one data structure than
> 10 functions on 10 data structures.

In a recent talk about reducers (available
[here](https://www.youtube.com/watch?v=6mTbuzafcII)), Rich Hickey
playfully asserted that it might be even better to have 100 functions
operate on anything. This article aims to show the effective way clojure
offers to emulate common datastructures to use standard functions on
pretend datastructures.

Let's see how we can leverage the clojure
[transient](http://clojure.org/transients) interface to provide access
to simple key/value stores. For the purpose of this article we'll use
everyone's favorite outsourced heap: [redis](http://redis.io) hand in
hand with the [carmine](https://github.com/ptaoussanis/carmine) library.

This is what accessing redis looks like from
[carmine](https://github.com/ptaoussanis/carmine):

```clojure

(require '[taoensso.carmine :as r :refer [wcar get set del]])

(def server-spec {}) ;; will connect to localhost

(wcar server-spec
  (r/set "a" "b")
  (r/get "a") ;; => "b"
  (r/del "a"))
```

Ideally, we'd like to be able to treat redis like a standard transient
map:

```clojure

(def redis (instance->transient server-spec))

;; assuming we're starting from an empty redis instance
(pr-str redis)
;; => "{}"
(assoc! redis :a :a)
(:a redis)
;; => :a
(pr-str redis)
;; => "{:a :a}"
(assoc! redis :b {:a 0 :b 1})
;; => {:a :a, :b {:b 1, :a 0}}
(assoc! (:b redis) :c 2)
;; {:b 1, :a 0, :c 2}
(-> redis :b (dissoc! :c :b))
;; {:a 0}
(count redis)
;; => 2
(seq redis)
;; => ([:a :a] [:b {:a 0}])
(dissoc! redis :b)
(dissoc! redis :a)
;; => {}
(assoc! redis :c #{:foo :bar})
;; {:c #{:foo :bar}}
```

This article will walk you through how to achieve that, on top of the
[carmine](https://github.com/ptaoussanis/carmine) library presented
above.

Please note that while this makes for an interesting exercise with
protocols, it is not recommended to use it extensively, some of the
chosen strategies to make this example easier are suboptimal at best.

### Basic building blocks

To achieve homomorphism, clojure relies on
[interfaces](http://docs.oracle.com/javase/tutorial/java/concepts/interface.html),
they provide a uniform way to access resources implementing them.

Looking at clojure's source code, a mere two interfaces provide the
necessary functionality for setting, retrieving and removing keys:
`clojure.lang.ILookup` and `clojure.lang.ITransientMap`:

```java
/* src/jvm/clojure/lang/ILookup.java */
public interface ILookup{
  Object valAt(Object key);
  Object valAt(Object key, Object notFound);
}

/* src/jvm/clojure/lang/ITransientMap.java */
public interface ITransientMap extends ITransientAssociative, Counted{

  ITransientMap assoc(Object key, Object val);
  ITransientMap without(Object key);
  IPersistentMap persistent();
}
```

In the above protocols:

-   `valAt` provides lookups and is shared across transients and
    persistent structures
-   `assoc` mutates a transient map to set a key to a value
-   `without` mutates a transient map to remove a key

The simplest way to implement java interfaces or clojure
[protocols](http://clojure.org/protocols) is to make use of `reify`
which generates anonymous classes implementing a list of provide
protocol or interface.

With this, we can go on and write a fake transient:

```clojure
(ns transient.redis
  (:require [taoensso.carmine :as r :refer [wcarl]]))

(defn instance->transient
  [spec]
  (reify
    clojure.lang.ILookup
    (valAt [this k]
      (wcar spec (r/get k)))
    (valAt [this k default]
      (or (.valAt this k) default))
    clojure.lang.ITransientMap
    (assoc [this k v]
      (wcar spec (r/set k v))
      this) ;; transients always return themselves on mutation.
    (without [this k]
      (wcar spec (r/del k))
      this)))
```

This gives a nice and clean interface to redis for keys:

```clojure
(def kv (instance->transient {})) ;; connect to localhost

(assoc! kv "a" "b")
(get kv "a")
(dissoc! kv "a")
```

### Taking advantage of homoiconicity

One of the nice properties of clojure is its homoiconic nature, meaning
that all standard data can be printed and read back in by the reader
without loss of information. Clojure even now provides a way to add new
types to the reader through [tagged
literals](http://clojure.org/reader#The Reader--Tagged Literals).

A first improvement we can make to our implementation is to use
[`pr-str`](http://clojure.github.io/clojure/clojure.core-api.html#clojure.core/pr-str)
and
[`read-string`](http://clojure.github.io/clojure/clojure.core-api.html#clojure.core/pr-str)
as a cheap serializer/deserializer.

```clojure
(ns transient.redis
  (:refer-clojure :exclude [read-string])
  (:require [taoensso.carmine :as r :refer [wcarl]]
            [clojure.edn :refer [read-string]]))

(defn instance->transient
  [spec]
  (reify
    clojure.lang.ILookup
    (valAt [this k]
      (when-let [res (wcar spec (r/get (pr-str k)))]
         (read-string res)))
    (valAt [this k default]
      (or (.valAt this k) default))
    clojure.lang.ITransientMap
    (assoc [this k v]
      (wcar spec (r/set (pr-str k) (pr-str v)))
      this)
    (without [this k]
      (wcar spec (r/del (pr-str k)))
      this)))
```

You'll note that in the above, we're using `clojure.edn/read-string`
instead of `clojure.core/read-string`. This is because
`clojure.core/read-string` is not safe for arbitrary inputs since it
might end up calling eval on input.

### More protocols

We've now reached a good first step for our key value interface. We
could go a bit further though, since redis supports data-types which
resemble clojure's. If we look at redis `sets`, `lists` and `hashes`,
they map to clojure `sets`, `vectors` and `maps` without to much
contorsion.

Fortunately, clojure provides transient versions of `sets`, `vectors`
and `maps`. To coerce our redis connection instance we will need to
implement a variety of interfaces described below.

1.  Counted

    This protocol should be implemented by all types and provides a way
    to yield the length of a collection.

    ```java
    public interface Counted {
      int count();
    }
    ```

2.  Seqable

    This protocol is implemented by any datastructure which can be
    coerced to a seq:

    ```java
    public interface ISeq {
      ISeq seq();
    }
    ```

    When calling `seq` on a map, it is expected to receive a list of
    `IMapEntry` structures as defined in the following interface:

    ```java
    public interface IMapEntry extends Map.Entry{
      Object key();
      Object val();
    }
    ```

    Map entries also happen to implement `Counted`, `Indexed` (see
    below) and `Seqable`. This is the protocol that allows you to write:

    ```clojure
    (map key {:a 0 :b 1})
    ```

3.  Indexed

    This protocol allows lookup in collections with
    [`nth`](http://clojure.github.io/clojure/clojure.core-api.html#clojure.core/nth):

    ```java
    public interface Indexed extends Counted{
      Object nth(int i);
      Object nth(int i, Object notFound);
    }
    ```

4.  ITransientVector

    Much like `ITransientAssociative`, `ITransientVector` gives a way to
    mutate on vector like structures:

    ```java
    public interface ITransientVector extends ITransientAssociative, Indexed{
      ITransientVector assocN(int i, Object val);
      ITransientVector pop();
    }
    ```

5.  ITransientSet

    `ITransientSet` completes the list of transient collections

    ```java
    public interface ITransientSet extends ITransientCollection, Counted{
      public ITransientSet disjoin(Object key) ;
        public boolean contains(Object key);
        public Object get(Object key);
    }
    ```

### Redis Operations

Let's now look at how the redis world can be mapped to the clojure
world.

1.  Redis Instance

    The global redis instance can be seen as a map, just like in our
    first example. If we want it to implement `Seqable` and `Counted`,
    there is no other choice but to issue the redis command `KEYS *` and
    count the results for `Counted` or map them to clojure values for
    `Seqable`.

    We have already seen how to implement `ILookup` and `ITransientMap`
    above, but we'll add a twist, when creating values, instead of
    always using the `SET` command, we can look at the type of value
    we're fed with
    [set?](http://clojure.github.io/clojure/clojure.core-api.html#clojure.core/set?),
    [map?](http://clojure.github.io/clojure/clojure.core-api.html#clojure.core/map?)
    and
    [sequential?](http://clojure.github.io/clojure/clojure.core-api.html#clojure.core/sequential?)
    to create matching types in redis (`set`, `hash` or `list`) while
    still defaulting to string keys.

    Likewise, when retrieving keys, we can use the redis `TYPE` command
    to lookup the key type and yield a transient vector, map or set when
    we encounter the matching redis types.

    `without` does not need to change, since it works on any key type.

    This gives us and updated `instance->transient`:

    ```clojure
    clojure.lang.ILookup
    (valAt [this k]
      (let [k    (pr-str k)
            type (wcar spec (r/type k))]
        (condp = type
          "string" (read-string (wcar spec (r/get k)))
          "hash"   (hash->transient spec k)
          "list"   (list->transient spec k)
          "set"    (set->transient spec k)
          "none"   nil
          (throw (ex-info "unsupported redis type" {:type type})))))
    clojure.lang.ITransientMap
    (assoc [this k v]
      (let [k (pr-str k)]
        (cond
         (set? v)        (doseq [member v]    ;; Call SADD on all members
                           (wcar spec (r/sadd k (pr-str member))))
         (map? v)        (doseq [[subk v] v]  ;; Call HSET on all entries
                           (wcar spec (r/hset k (pr-str subk) (pr-str v))))
         (sequential? v) (doseq [e v]         ;; Call LPUSH on all entries
                           (wcar spec (r/lpush k (pr-str e))))
          ;; Default to a plain SET
         :else           (wcar spec (r/set k (pr-str v)))))
      this)
    ```

    As explained above we can now also implement `Counted` and
    `Seqable`:

    ```clojure
    clojure.lang.Counted
    (count [this]
      (count (wcar spec (r/keys "*"))))
    clojure.lang.Seqable
    (seq [this]
      (let [keys (wcar spec (r/keys "*"))]
        (for [k keys]
          (->mapentry (read-string k) (.valAt this (read-string k))))))
    ```

    Beware that calling `KEYS *` is very suboptimal and should not be
    done in real life scenarios.

    We're also missing the `->mapentry` function above, which can be
    simply be:

    ```clojure
    (defn ->mapentry
      [k v]
      (reify
        clojure.lang.Indexed
        (nth [this i]         (nth [k v] i))     ;; carry over
        (nth [this i def]     (nth [k v] i def)) ;; carry over to nth
        clojure.lang.Seqable
        (seq [this]           (list k v))        ;; we know all elems
        clojure.lang.Counted
        (count [this]         2)                 ;; always two elems
        clojure.lang.IMapEntry
        (getKey [this]        k)                 ;; IMapEntry extends Map.Entry
        (getValue [this]      v)                 ;;
        (key [this]           k)                 
        (val [this]           v)))
    ```

2.  Redis Hashes

    Redis hashes will implement the same interfaces than redis
    instances: `ILookup`, `ITransientMap`, `Counted` and `Seqable`. The
    logic will closely resemble our initial version. Lookups will be
    done using `HGET`, removals with `HDEL`. To count elements or coerce
    a hash to a seq, we can count on the `HGETALL` command which yields
    a list containing keys and values. Since the output list is
    flattened we can use `(partition 2)` to obtain the desired
    structure:

    ```clojure
    (defn hash->transient
      [spec k]
      (reify
        clojure.lang.ILookup
        (valAt [this subk]
          (when-let [res (wcar spec (r/hget k (pr-str subk)))]
            (read-string res)))
        (valAt [this subk default]
          (or (.valAt this subk) default))
        clojure.lang.ITransientMap
        (assoc [this subk v]
          (wcar spec (r/hset k (pr-str subk) (pr-str v)))
          this)
        (without [this subk]
          (wcar spec (r/hdel k (pr-str subk)))
          this)
        clojure.lang.Counted
        (count [this]
          (count (partition 2 (wcar spec (r/hgetall k)))))
        clojure.lang.Seqable
        (seq [this]
          (for [[k v] (partition 2 (wcar spec (r/hgetall k)))]
            (->mapentry (read-string k)
                        (read-string v))))))
    ```

3.  Redis Sets

    Redis sets are very similar to hashes. Additions to the set are done
    with `SADD`, deletions with `SREM`, we have an efficient way of
    counting the set with `SCARD`. `SISMEMBER` will tell us if we have a
    matching member in our set (it returns either 0 or 1, so we need to
    coerce it to a boolean with `pos?`). Last, `SMEMBERS` will help in
    implementing `seq`:

    ```clojure
    (defn set->transient
      [spec k]
      (reify
        clojure.lang.Counted
        (count [this]
          (wcar spec (r/scard k)))
        clojure.lang.Seqable
        (seq [this]
          (map read-string (wcar spec (r/smembers k))))
        clojure.lang.ITransientCollection
        (conj [this v]
          (wcar spec (r/sadd k (pr-str v)))
          this)
        clojure.lang.ITransientSet
        (disjoin [this v]
          (wcar spec (r/srem k (pr-str v)))
          this)
        (contains [this v]
          (let [member (wcar spec (r/sismember k (pr-str v)))]
            (pos? member)))
        (get [this v]
          (when (.contains this v)
            v))))
    ```

4.  Redis Lists

    To finish off with our tour of structures, vectors and lists will be
    mapped to redis lists. A list's length is reported by `LLEN`, we can
    retrieve all members with `LRANGE 0 -1` while `LSET`, `LPOP` and
    `LPUSH` will implement mutation operations:

    ```clojure
    (defn list->transient
      [spec k]
      (reify
        clojure.lang.Counted
        (count [this]
          (wcar spec (r/llen k)))
        clojure.lang.Seqable
        (seq [this]
          (map read-string (wcar spec (r/lrange k 0 -1))))
        clojure.lang.ITransientCollection
        (conj [this v]
          (wcar spec (r/lpush k (pr-str v)))
          this)
        clojure.lang.ITransientVector
        (assocN [this index v]
          (wcar spec (r/lset k index v))
          this)
        (pop [this]
          (wcar spec (r/lpop k))
          this)))
    ```

### Behaving like functions

A property of both sets and maps is to behave like functions which test
for membership (on sets) and lookup keys (on maps).

This is done by yet another interface: `IFn`:

```java
public interface IFn extends Callable, Runnable{
  public Object invoke() ;
  public Object invoke(Object arg1) ;
  public Object invoke(Object arg1, Object arg2) ;
  /* ... */
}
```

We can add the signature to our set and map implementations:

```clojure
;; hash->transient
    clojure.lang.IFn
    (invoke [this subk]
      (.valAt this subk))

;; set->transient
    clojure.lang.IFn
    (invoke [this member]
      (when (.contains this member)
        member))
```

Our transients now fully behave like clojure datastructures!

### The cherry on top: pretty printing

Our transient facade is getting there, but we're still faced with a
problem: in doesn't look good in the repl. Without going any further a
redis instance looks like this:
`#<transient$instance$reify__8580 redis.transient$instance$reify__8580@3850ea4b>`.
Likewise, when dealing with sets, lists or maps.

By digging around, we find that printing is done with `clojure.core/pr`
which ultimately calls `clojure.core/pr-on`, defined at
<https://github.com/clojure/clojure/blob/eccff113e7d68411d60f7204711ab71027dc5356/src/clj/clojure/core.clj#L3532-L3544>.

We can rely on `print-method`'s ability to look either at the class of
an object or at its metadata to dispatch to the appropriate pretty
printer:

```clojure
(defmulti print-method (fn [x writer]
                         (let [t (get (meta x) :type)]
                           (if (keyword? t) t (class x)))))
```

We can now write a dispatch method which expects our transient's
metadata to contain the keys `:type`, used by `print-method`'s dispatch
function, `prefix` and `suffix` specify how to enclose the contents of
collections.

```clojure
(defmethod print-method :redis
  [obj ^java.io.Writer writer]

  ;; extract additional metadata
  (let [{:keys [prefix suffix sep tuple?]} (meta obj)]
    (.write writer prefix)

    ;; ensure we have elements to show
    (when (pos? (count obj))
      (loop [[item & items] (seq obj)]
        ;; handle map tuples differently
        (if tuple?
          (do
            (print-method (key item) writer)
            (.write writer " ")
            (print-method (val item) writer))
          (print-method item writer))

        ;; show separator when there are more elems
        (when (seq items)
          (.write writer (str sep  " "))
          (recur items))))

    (.write writer suffix)))
```

With this in place we can add metadata to our closures:

```clojure
(defn ->mapentry
  [k v]
  ^{:type :redis :prefix "[" :suffix "]"}
  ...)

(defn hash->transient
  [k v]
  ^{:type :redis :prefix "{" :suffix "}" :sep "," :tuple? true}
  ...)

(defn set->transient
  [k v]
  ^{:type :redis :prefix "#{" :suffix "}"}
  ...)

(defn list->transient
  [k v]
  ^{:type :redis :prefix "[" :suffix "]"}
  ...)

(defn instance->transient
  [k v]
  ^{:type :redis :prefix "{" :suffix "}" :sep "," :tuple? true}
  ...)
```

### Wrapping up

All the bits are now in place, and the example code shown above works as
expected. I've posted the output at
<https://gist.github.com/618d49694df91710f1c2>.

I didn't publish a library on purpose since I don't think you should use
this for any serious work, but it does make for an interesting
playground.

While testing this implementation I found out that `contains?` cannot be
called on transient sets because of a limitation in the runtime, I
created a JIRA issue to discuss this here:
<http://dev.clojure.org/jira/browse/CLJ-1581>.
