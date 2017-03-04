#+title: A wrapping macro
#+date: 2011-08-06

### A bit of sugar

The **wrap-with** function described in my last post[^1] is useful, but
you still end-up having to write closures which might be confusing to
people who just want to write simple wrappers.

Fortunately, clojure provides the ability to enhance the language with
syntactic sugar for use cases such as this one.

### A word of warning

I'm obviously going to talk about macros in this article. I still think
one has to postpone the writing macros as much as possible, to avoid
creating code that feels too **magic** to the outside reader.

There are two use cases where resorting to macro is idiomatic, we'll
explore the first one here:

-   macros which help define symbols, usually named `def<resource>`
-   macros which wrap access to a resource within a closure, usually
    named `with-<resource>`

The first kind of macro is used on a common basis by the clojure
programmer: defn[^2]. Yep, that's right the idiomatic way to declare
functions in clojure is a macro that wraps a call to def.

The second kind's most popular example is with-open[^3] which encloses
access to a resource and ensures that it gets closed. The
**with-resource** calls have become common idioms in clojure libraries
and provide a great equivalent to the similar ruby co-block idiom. This
type of macros will be described in a later post though.

### Macro terminology

Macros need access to all kind of resources and reading them might be
hard on the eyes at first, several people have written on the subject of
macros, and books have been written that go into great detail on the
subject. So I'll just go with a cheat sheet:

1.  The body of a macro is usually quoted

    Macros insert are expanded to code, hence you must provide the
    **s-exprs** you want to be executed by quoting them otherwise they
    won't be executed at the time of execution.

    Beware that there are two types of quoting available in clojure:

    -   Standard quoting, using '
    -   Backtick quoting, using \` which expands forms into the current
        namespace, and is generally used for macros

2.  Accessing data from within the quoted **s-exprs**

    There are two ways to access data from within a quoted list of
    expressions:

    -   **unquote**: which takes the value of a symbol and replaces it
        in the expanded list, **\~expr**
    -   **unquote-splicing**: which takes the value of a symbol pointing
        to a list and expands it spliced, **\~@expr**

3.  The canonical unless example

    Unless is the most common example macro described, let's see how it
    is written

    ```clojure
    (defmacro unless [test & exprs]
      `(if (not ~test)
        (do ~@exprs)))
    ```

    Short but dense! The code reads like this:

    -   Define an unless macro which takes an arbitrary number of
        arguments, the first one being bound to **test**, the rest to a
        list called **exprs**
    -   Test the veracity of **test**
    -   Execute the expressions in a **do** block

### Wrapping up

Building on our previous function **wrap-with**, we can then help people
write wrapper functions more easily:

```clojure
(defmacro defwrapper [wrapper-name handler bindings & exprs]
  `(def ~wrapper-name
    (fn [~handler]
      (fn ~bindings
        (do ~@exprs)))))
```

This is somewhat inelegant since we still need to supply a symbol which
is going to be bound to the handler. We can wrap it up using our
previous function:

```clojure
(defn to-be-wrapped [payload]
  (assoc payload :reply :ok))

(defwrapper wrap-add-foo handler [payload]
  (handler (assoc payload :foo :bar)))

(wrap-with to-be-wrapped [wrap-add-foo])
```

### Room for improvement

Now let's play a bit of magic, how about creating a macro which rebinds
a symbol altogether:

```clojure
(defmacro wrap-around [handler bindings & exprs]
  `(let [x#    ~handler
         meta#  (meta (var ~handler))]
     (def ~handler
       (fn ~bindings
         (let [~handler x#] 
           (do ~@exprs))))
     (alter-meta! (var ~handler) merge meta#)))
```

Notice the last call to alter-meta![^4] which preserves the initial
var's metadata, such as **:tag** or **:arglists**. Now here are the
macros in context:

```clojure
(wrap-around send-command [payload]
  (send-command (assoc payload :foo :bar)))

;; store elapsed time in 
(wrap-around send-command [payload]
  (let [start (System/nanoTime)]
    (assoc (send-command payload) (- (System/nanoTime) start))))
```

### Closing words

This is just a peak into the power of macros in clojure, and it was a
fun journey getting to the bottom of the last macro. However the last
form complicates reading to some extend and should thus be avoided if
possible.

[^1]: <http://spootnik.org/blog/2011/08/04/clojure-wrappers>

[^2]: <http://clojure.github.com/clojure/clojure.core-api.html#clojure.core/defn>

[^3]: <http://clojure.github.com/clojure/clojure.core-api.html#clojure.core/with-open>

[^4]: <http://clojure.github.com/clojure/clojure.core-api.html#clojure.core/alter-meta>!
