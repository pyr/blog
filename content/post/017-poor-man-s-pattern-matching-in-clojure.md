#+title: Poor man's pattern matching in clojure
#+date: 2013-05-21

A quick tip which helped me out in a few situations. I'd be inclined to
point people to [core.match](https://github.com/clojure/core.match) for
any matching needs, but the fact that it doesn't play well with
clojure's ahead-of-time (`AOT`) compilation requires playing dirty
dynamic namespace loading tricks to use it.

A common case I stumbled upon is having a list of homogenous records -
say, coming from a database, or an event stream - and needing to take
specific action based on the value of several keys in the records.

Take for instance an event stream which would contain homogenous records
of with the following structure:

```clojure
[{:user      "bob"
  :action    :create-ticket
  :status    :success
  :message   "succeeded"
  :timestamp #inst "2013-05-23T18:19:39.623-00:00"}
 {:user      "bob"
  :action    :update-ticket
  :status    :failure
  :message   "insufficient rights"
  :timestamp #inst "2013-05-23T18:19:40.623-00:00"}
 {:user      "bob"
  :action    :delete-ticket
  :status    :success
  :message   "succeeded"
  :timestamp #inst "2013-05-23T18:19:41.623-00:00"}]
```

Now, say you need do do a simple thing based on the output of the value
of both `:action` and `:status`.

The first reflex would be to do this within a `for` or `doseq`:

```clojure
(for [{:keys [action status] :as event}]
   (cond
     (and (= action :create-ticket) (= status :success)) (handle-cond-1 event)
     (and (= action :update-ticket) (= status :success)) (handle-cond-2 event)
     (and (= action :delete-ticket) (= status :failure)) (handle-cond-3 event)))
```

This is a bit cumbersome. A first step would be to use the fact that
clojure seqs and maps can be matched, by narrowing down the initial
event to the matchable content. `juxt` can help in this situation, here
is its doc for reference.

> Takes a set of functions and returns a fn that is the juxtaposition of
> those fns. The returned fn takes a variable number of args, and
> returns a vector containing the result of applying each fn to the args
> (left-to-right).

I suggest you play around with `juxt` on the repl to get comfortable
with it, here is the example usage we're interested in:

```clojure
(let [narrow-keys (juxt :action :status)]
   (narrow-keys {:user      "bob"
                 :action    :update-ticket
                 :status    :failure
                 :message   "insufficient rights"
                 :timestamp #inst "2013-05-23T18:19:40.623-00:00"}))
 => [:update-ticket :failure]
```

Given that function, we can now rewrite our condition handling code in a
much more succint way:

```clojure
(let [narrow-keys (juxt :action :status)]
  (for [event events]
    (case (narrow-keys event)
      [:create-ticket :success] (handle-cond-1 event)
      [:update-ticket :failure] (handle-cond-2 event)
      [:delete-ticket :success] (handle-cond-3 event))))
```

Now with this method, we have a perfect candidate for a multimethod:

```clojure
(defmulti handle-event (juxt :action :status))
(defmethod handle-event [:create-ticket :success]
   [event]
   ...)
(defmethod handle-event [:update-ticket :failure]
   [event]
   ...)
(defmethod handle-event [:delete-ticket :success]
   [event]
   ...)
```

Of course, for more complex cases and wildcard handling, I suggest
taking a look at [core.match](https://github.com/clojure/core.match).
