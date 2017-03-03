#+title: Building an atomic database with clojure
#+date: 2016-12-17

Atoms provide a way to hold onto a value in clojure and perform
thread-safe transitions on that value. In a world of immutability, they
are the closest equivalent to other languages' notion of variables you
will encounter in your daily clojure programming.

Storing with an atom
====================

One of the frequent uses of atoms is to hold onto maps used as some sort
of cache. Let's say our program stores a per-user high score for a game.

To store high-scores in memory atoms allow us to implement things very
quickly:

```clojure
(ns game.scores
  "Utilities to record and look up game high scores")

(defn make-score-db
  "Build a database of high-scores"
  []
  (atom nil))

(def compare-scores
  "A function which keeps the highest numerical value.
   Handles nil previous values."
  (fnil max 0))

(defn record-score!
  "Record a score for user, store only if higher than
   previous or no previous score exists"
  [scores user score]
  (swap! scores update user compare-scores score))

(defn user-high-score
  "Lookup highest score for user, may yield nil"
  [scores user]
  (get @scores user))

(defn high-score
  "Lookup absolute highest score, may yield nil
   when no scores have been recorded"
  [scores]
  (last (sort-by val @scores)))
```

In the above we have put together a very simple record mechanism, which
through the use of `defonce` keeps scores across application reloads or
namespace reevaluations. Ideally this should be provided as a component,
but for the purposes of this post we will keep things as simple as
possible.

Using the namespace works as expected:

```clojure
(def scores (make-score-db))
(high-score scores)         ;; => nil
(user-high-score scores :a) ;; => nil
(record-score! scores :a 2) ;; => {:a 2}
(record-score! scores :b 3) ;; => {:a 2 :b 3}
(record-score! scores :b 1) ;; => {:a 2 :b 3}
(record-score! scores :a 4) ;; => {:a 4 :b 3}
(user-high-score scores :a) ;; => 4
(high-score scores)         ;; => [:a 4]
```

Atom persistence
================

This is all old news to most. What I want to showcase here is how the
`add-watch` functionality on top of atoms can help serializing atoms
like these.

First lets consider the following:

-   We want to store our `high-scores` state to disk
-   The content of `high-scores` contains no unprintable values

It is thus straightforward to write a serializer and deserializer for
such a map:

```clojure
(ns game.serialization
  "Serialization utilities"
   (:require [clojure.edn :as edn]))

(defn dump-to-path
  "Store a value's representation to a given path"
  [path value]
  (spit path (pr-str value)))

(defn load-from-path
  "Load a value from its representation stored in a given path.
   When reading fails, yield nil"
  [path]
  (try
    (edn/read-string (slurp path))
    (catch Exception _)))
```

This also works as expected:

```clojure
(dump-to-path "/tmp/scores.db"
  {:a 0 :b 3 :c 3 :d 4})          ;; => nil
(load-from-path "/tmp/scores.db") ;; => {:a 0 :b 3 :c 3 :d 4}
```

With these two separate namespaces, we are now left figuring out how to
persist our high-score database. To be as faithful as possible, we will
avoid techniques such as doing regular snapshots. Instead we will reach
out to `add-watch` which has the following signature `(add-watch
reference key fn)` and documentation:

> Adds a watch function to an agent/atom/var/ref reference. The watch fn
> must be a fn of 4 args: a key, the reference, its old-state, its
> new-state. Whenever the reference's state might have been changed, any
> registered watches will have their functions called. The watch fn will
> be called synchronously, on the agent's thread if an agent, before any
> pending sends if agent or ref. Note that an atom's or ref's state may
> have changed again prior to the fn call, so use old/new-state rather
> than derefing the reference. Note also that watch fns may be called
> from multiple threads simultaneously. Var watchers are triggered only
> by root binding changes, not thread-local set!s. Keys must be unique
> per reference, and can be used to remove the watch with remove-watch,
> but are otherwise considered opaque by the watch mechanism.

Our job is thus to write a 4 argument function of the atom itself, a key
to identify the watcher, the previous and new state.

To persist each state transition to a file, we can use our
`dump-to-path` function above as follows:

```clojure
(defn persist-fn
  "Yields an atom watch-fn that dumps new states to a path"
  [path]
  (fn [_ _ _ state]
    (dump-to-path path state)))

(defn file-backed-atom
   "An atom that loads its initial state from a file and persists each new state
    to the same path"
   [path]
   (let [init  (load-from-path path)
         state (atom init)]
     (add-watch state :persist-watcher (persist-fn path))
     state))
```

Wrapping up
===========

The examples above can now be exercized using our new `file-backed-atom`
function:

```clojure
(def scores (file-backed-atom "/tmp/scores.db"))
(high-score scores)         ;; => nil
(user-high-score scores :a) ;; => nil
(record-score! scores :a 2) ;; => {:a 2}
(record-score! scores :b 3) ;; => {:a 2 :b 3}
(record-score! scores :b 1) ;; => {:a 2 :b 3}
(record-score! scores :a 4) ;; => {:a 4 :b 3}
(user-high-score scores :a) ;; => 4
(high-score scores)         ;; => [:a 4]
```

The code presented here is available at
<https://gist.github.com/2a32f64a308e5691ad96ef71030a6dfe>
