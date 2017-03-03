#+title: Introducing: TRON
#+date: 2011-07-11

I just uploaded a small library to github (and clojars), It's a
generalisation of what I use for recurrent tasks in my clojure
programs, the fact that the recent pragprog [article](http://pragprog.com/magazines/2011-07/create-unix-services-with-clojure) had a
handrolled version of it too convinced me it was worth putting
together in a lib.

The library provides an easy mechanism to register process for later
execution, either recurrent or ponctual. It is called TRON,
replacing CRON's C which stands for command - as in: command run on - with a T for task.

Here is a short excerpt of what it can do:

```
{{< highlight clojure >}}(ns sandbox
  (:require tron))

(defn- periodic [] (println "periodic"))
(defn- ponctual [] (println "ponctual"))

;; Run the fonction 10 seconds from now
(tron/once ponctual 10000)

;; Run the periodic function every second
(tron/periodically :foo periodic 1000)

;; Cancel the periodic run 5 seconds from now
(tron/once #(tron/cancel :foo) 5000){{< /highlight >}}
```

The code is hosted on github: https://github.com/pyr/tron, the full
annotated source can be found [here](http://spootnik.org/files/tron.html) and the artifacts are already
on clojars (see [here](http://clojars.org/tron)).
The library still needs a better way of expressing delays which will
be worked on, and might benefit from macros allowing you to embed the
body to be executed later. All in due time.
