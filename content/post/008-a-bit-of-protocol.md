#+title: A bit of protocol
#+date: 2011-08-12

### Protocols and mixins

I recently had to implement something in clojure I've done many times in
ruby, which involved using protocols. I thought it would be a nice
example of comparing class re-opening in ruby and protocol extension in
clojure.

### The problem

I use cassandra, and in many places, cassandra needs to work with UUID
types a lot. When exposing results over `JSON`, this is often a problem
since standard serializers don't support these types.

What we want to do in ruby and clojure is simple:

```ruby
require 'simple_uuid'
require 'json'

# This fails
{:uuid => SimpleUUID::UUID.new }.to_json
```

This code fails because the `json` module looks for a `to_json` method
in each object. Failing to do so, it calls `Object#to_s.to_json`. Now
this would work fine if `to_s` gave a good textual representation of a
UUID, but it returns the byte array for that UUID.

```clojure
(ns foo
 (:use clojure.data.json)
 (:import java.util.UUID))

; This fails
(println (json-str {:uuid (UUID/randomUUID)}))
```

In clojure we are informed that `java.util.UUID` doesn't respond to
`write-json`

\#\# Fixing the problem in ruby

How to fix this in ruby is no problem, and widely known, since the
`simple_uuid` gem provides a `to_guid` method which returns the textual
representation, it's as easy as:

```ruby
require 'simple_uuid'
require 'json'

module SimpleUUID
  class UUID
    def to_json *args
      "\"#{to_guid}\""
    end
  end
end

puts({:uuid => SimpleUUID::UUID.new}.to_json)
```

This was simple enough, reopening the module then class is allowed - and
to some extent, encouraged - in ruby. We just added a `to_json` method
which is what the `JSON` module looks for when walking through objects.

### Fixing the problem in clojure

clojure has the ability to provide so-called **protocols**, similar to
java **interfaces**. Protocols are defined with `defprotocol` and
implemented anywhere. Here is the appropriate bit from
`clojure.data.json`

```clojure
;;; JSON PRINTER

(defprotocol Write-JSON
  (write-json [object out escape-unicode?]
              "Print object to PrintWriter out as JSON"))
```

This defines that `write-json` will be dispatched based on class to an
appropriate writer. The clojure page on protocols[^1] has all the
detailed information, but I'll focus on the `extend` part here, which
allows to extend a type with new protocol implementations. `extend`
expects a type then pairs of protocol names to maps, the map containing
function name to implementation mappings.

Protocol functions always have the object they need to operate on as
their first argument, here the function takes two additional arguments

-   `out` which is the output stream the representation should be pushed
    to
-   `escape-unicode?` which determines whether unicode characters should
    be escaped.

Following that logic, the implementation can now be written like this:

```clojure
(ns somewhere
  (:import java.util.UUID)
  (:use clojure.data.json))

(defn write-json-uuid [obj out escape-unicode?]
  (binding [*out* out]
    (pr (.toString obj))))

(extend UUID Write-JSON
  {:write-json write-json-uuid})

(println (json-str {:uuid (UUID/randomUUID)}))
```

### Writing the protocol extension

The actual function `write-json-uuid` is quite simple, I initially wrote
it as:

```clojure
(defn write-json-uuid [obj out escape-unicode?]
  (.print out (pr-str (.toString obj))))
```

But it seems a bit overkill to go to the trouble of writing to a string,
then pushing that string out to the **writer** object.

### Dynamic bindings

A small digression is needed here, clojure has **dynamic** symbols,
defined like so: `(def ^{:dynamic true} *my-dyn-symbol*)` The enclosing
stars are a convention but widely used.

Dynamic symbols can be manipulated with `binding`, which operates like
`let` but the bindings will follow the rest of the execution enclosed,
not just the function's context.

Clojure uses the `*out*` symbol everywhere to denote the current output
stream, many functions operate on it, `pr` is among them.

By binding `*out*` to the stream that was given as argument to
`write-json`, `pr` can simply be called on the function.

### Closing words

The most common dispatching idiom in clojure is `defmethod=/=defmulti`,
but protocols also provide a very fast and useful way to implement
polymorphism in clojure. It's also nice to note that the implementation
wasn't longer in clojure than ruby.

[^1]: <http://clojure.org/protocols>
