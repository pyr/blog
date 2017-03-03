+++
title = "Poor man's dependency injection in Clojure"
date = "2014-01-25"
slug = "poor-mans-dependency-injection-in-clojure"
+++

When writing daemons in clojure which need configuration, you often find
yourself in a situation where you want to provide users with a way of
overriding or extending some parts of the application.

All popular daemons provide this flexibility, usually through
**modules** or **plugins**. The extension mechanism is a varying beast
though, lets look at how popular daemons work with it.

-   [nginx.org](http://nginx.org) , written in C, uses a function
    pointer structure that needs to be included at compile time. Modules
    are expected to bring in their own parser extensions for the
    configuration file.

-   [collectd](http://collectd.org), written in C uses function pointer
    structures as well but allows dynamic loading through **ld.so**.
    Additionally, the exposed function are expected to work with a
    pre-parsed structure to configure their behavior

-   [puppet](http://puppetlabs.com), written in Ruby, lets additional
    module reopen the puppet module to add functionality

-   [cassandra](http://cassandra.apache.org), written in Java parses a
    YAML configuration file which specifies classes which will be loaded
    to provide a specific functionality

While all these approaches are valid, Cassandra's approach most closely
ressembles what you'd expect a clojure program to provide since it runs
on the JVM. That particular type of behavior management - while usually
being defined in XML files, since it is so pervasive in the Java
community - is called **Dependency Injection**.

### Dependency injection on the JVM

The JVM brings two things which simplify creating a daemon with
configurable behavior:

-   **Interfaces** let you define a contract an object must satisfy
-   **Classpaths** let you add code to a project at run-time (not
    build-time)

Cassandra's YAML configuration takes advantage of these two properties
to let you swap implementation for different types of authenticators,
snitches or partitioners.

### A lightweight approach in clojure

So let's mimick cassandra and write a simple configuration file which
allows modifying behavior.

Let's pretend we have a daemon which listens for data through
transports, and needs to store it using a storage mechanism. A good
example would be a log storage daemon, listening for incoming log lines,
and storing them somewhere.

For such a daemon, the following "contracts" emerge:

-   **transports**: which listen for incoming log lines
-   **codecs**: which determine how data should be de-serialized
-   **stores**: which provide a way of storing data

This gives us the following clojure protocols:

```clojure
(defprotocol Store
  (store! [this payload]))

(defprotocol Transport
  (listen! [this sink]))

(defprotocol Codec
  (decode [this payload]))

(defprotocol Service
  (start! [this]))
```

This gives you the ability to build an engine which has no knowledge of
underlying implementation and can be very easily tested and inspected:


```clojure
(defn reactor
  [transports codec store]
  (let [ch  (chan 10)]
    (reify
      Service
      (start! [this]
        (go-loop []
          (when-let [msg (<! ch)]
            (store! store (decode codec msg))
              (recur)))
        (doseq [transport transports]
          (start! transport)
          (listen! transport sink))))))
```

As shown above, we use reify to create an instance of an object honoring
a specific protocol (or Java interface).

Here are simplistic implementations of an EDN codec, an stdout store and
an stdin transport:

```clojure
(defn edn-codec [config]
  (reify Codec
    (decode [this payload]
      (read-string payload))))

(defn stdout-store [config]
  (reify
    Store
    (store! [this payload]
      (println "storing: " payload))))

(defn stdin-transport [config]
  (let [sink (atom nil)]
    (reify
      Transport
      (listen! [this new-sink]
        (reset! sink new-sink))
      Service
      (start!
        (future
          (loop []
            (when-let [input (read-line)]
              (>!! @sink input)
              (recur))))))))
```

Note that each implementation gets passed a configuration variable -
which will be useful.

### A yaml configuration

Now that we have our protocols in place let's see if we can come up with
a sensible configuration file for our mock daemon:

```yaml
codec:
  use: mock-daemon.codec/edn-codec
transports:
  stdin:
    use: mock-daemon.transport.stdin/stdin-transport
store:
  use: mock-daemon.transport.stdin/stdout-store
```

Our config contains three keys. `codec` and `store` are maps containing
at least a `use` key which points to a symbol that will yield an
instance of a class implementing the `Codec` or `Store` protocol.

Now all that remains to be done is having an an easy way to load this
configuration and produce a codec, transports and stores from it.

### Clojure introspection

Parsing the above configuration from yaml, with for instance
`clj-yaml.core/parse-string`, will yield a map, if we only look at the
codec part we would have:

```clojure
{:codec {:use "mock-daemon.codec/edn-codec"}}
```

Our goal will be to retrieve an instance reifying `Codec` from the
string `mock-daemon.codec/edn-codec`.

This can be done in two steps:

-   Retrieve the symbol
-   Call out the function

To retrieve the symbol, this simple bit will do:

```clojure
(defn find-ns-var
  [candidate]
  (try
    (let [var-in-ns  (symbol candidate)
          ns         (symbol (namespace var-in-ns))]
      (require ns)
      (find-var var-in-ns))
    (catch Exception _)))
```

We first extract the namespace out of the namespace qualified var and
require it, then get the var. Any errors will result in nil being
returned.

Now that we have the function, it's straightforward to call it with the
config:

```clojure
(defn instantiate
  [candidate config]
  (if-let [reifier (find-ns-var candidate)]
    (reifier config)
    (throw (ex-info (str "no such var: " candidate) {}))))
```

We can now tie these two functions:

```clojure
(defn get-instance
  [config]
  (let [candidate (-> config :use name symbol)
        raw-config (dissoc config :use)]
    (instantiate candidate raw-config)))
```

These three snippets are the only bits of introspection you'll need and
are the core of our solution.

### Tying it together

We can now make use of get-instance in our configuration loading code:

```clojure
(defn load-path
  [path]
  (-> (or path
          (System/getenv "CONFIGURATION_PATH")
          "/etc/default_path.yaml")
      slurp
      parse-string))

(defn get-transports
  [transports]
  (zipmap (keys transports)
          (mapv get-instance (vals transports))))

(defn init
  [path]
  (try
    (-> (load-path path)
        (update-in [:codec] get-instance)
        (update-in [:store] get-instance)
        (update-in [:transports] get-transports))))
```

### Using it from your main function

Now that all elements are there, starting up the daemon ends up only
creating the configuration and working with protocols by calling our
previous `reactor` function.

```clojure
(defn main
  [& [config-file]]
  (let [config     (config/init config-file)
        codec      (:codec config)
        store      (:store config)
        transports (:transports config)
        reactor    (reactor transports codec store)]
    (start! reactor)))
```

By having `reactor` decoupled from the implementations of transports,
codecs and the likes, testing the meat of the daemon becomes dead
simple; a reactor can be started with dummy transports, stores and
codecs to validate its inner-workings.

I hope this gives a good overview of simple techniques for building
daemons in clojure.
