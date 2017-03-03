#+title: Heads up for Clojure library writers
#+date: 2014-11-03

Clojure 1.7 is around the corner, we're already at [version
1.7.0-alpha3](http://search.maven.org/#artifactdetails%7Corg.clojure%7Cclojure%7C1.7.0-alpha3%7Cjar).
Fortunately, the iterative approach of clojure taken since 1.3 means
that upgrading from one version to the next usually only needs a change
in your `project.clj` file (as well as working interop accross versions,
which is always nice).

There are some neat changes in 1.7, the most notable being the addition
of transducers. I recommend reading through the introduction to
transducers at
<http://blog.cognitect.com/blog/2014/8/6/transducers-are-coming> and the
video from strange loop at
<https://www.youtube.com/watch?v=6mTbuzafcII>.

A much smaller addition to 1.7 is the introduction of the `update`
function in `clojure.core`. `update` is directly equivalent to
`update-in` but operates on a single key.

When you wrote:

``` clojure
(-> input-map
    (update-in [:my-counter-key] inc))
```

You will now be able to write:

``` clojure
(-> input-map
    (update :my-counter-key inc))
```

This has been a long-wanted change and brings `update` on par with
`get`, and `assoc` which have their `-in` suffixed equivalents.

One direct consequence of the change is that if you have a namespace
that exposes an `update` function, you will need to deal with the fact
that it will now clash with `clojure.core/update` since `clojure.core`
is referred by default in all namespaces.

You have two strategies to deal with that fact:

-   Rename the function (which can be a bit intrusive)
-   Prevent `clojure.core/update` from being referred in your namespace

For the second strategy, you will only need to use the following form in
your namespace declaration:

``` clojure
(ns my.namespace
 (:require [...])
 (:refer-clojure :exclude [update]))
```

If you don't, your library consumers will have to deal with messages
such as:

``` clojure
WARNING: update already refers to: #'clojure.core/update in namespace: foo.core, being replaced by: #'foo.core/update
WARNING: update already refers to: #'clojure.core/update in namespace: user, being replaced by: #'foo.core/update
```
