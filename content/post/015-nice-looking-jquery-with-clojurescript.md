#+date: 2012-11-22
#+title: Nice looking JQuery with Clojurescript

I did a bit of frontend work for internal tools recently and chose to go
with clojurescript, for obvious reasons.

Like clojure, clojurescript supports macros which let you express common
lengthy idioms. I use the [jayq](https://github.com/ibdknox/jayq)
library to interact with the browser since JQuery is the de-facto
standard and battle tested.

A standard call to a JSON get in JQuery looks like this:

```javascript
$.getJSON('ajax/test.json', function(data) {
   doSomethingWith(data);
});
```

Which is really a call to:

```javascript
$.ajax({
  url: 'ajax/test.json',
  dataType: 'json',
  success: function(data) { doSomethingWith(data); }
});
```

Now when using `jayq` this would translate to:

```clojure
(ajax "ajax/test.json"
      {:dataType "json"
       :success  (fn [data] (do-something-with data))})
```

This is just as simple, but a bit lacking in terms of readability, with
a simple, add to this that you might want to check the `done` status of
the resulting future and you end up with:

```clojure
(let [ftr (ajax "ajax/test.json"
                {:dataType "json"
                 :success  (fn [data] (do-something-with data))})]
  (.done ftr (fn [] (refresh-view))))
```

Thankfully, with macros we can make this much prettier, with these two
simple macros:

```clojure
(defmacro when-done
  [ftr & body]
  `(.done ~ftr (fn [] ~@body)))

(defmacro with-json
  [sym url & body]
  `(jayq.core/ajax
    ~url
    {:dataType "json"
     :success  (fn [data#] (let [~sym (cljs.core/js->clj data#)] ~@body))}))
```

We can now write:

```clojure
(when-done (with-json data "ajax/test.json" (do-something-with data))
           (refresh-view))
```

Which clearly turns down the suck on the overall aspect of your callback
code.

There's a lot more to consider of course, as evidenced by [this
PR](https://github.com/ibdknox/jayq/pull/24), but it's a good showcase
of clojurescript's abilities.
