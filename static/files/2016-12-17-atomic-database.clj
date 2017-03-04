(ns game.score
  "Utilities to record and look up "
  (:require [clojure.edn :as edn]))

(defn make-score-db
  "Build a database of high scores"
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

(comment
  (def scores (file-backed-atom "/tmp/scores.db"))
  (high-score scores)         ;; => nil
  (user-high-score scores :a) ;; => nil
  (record-score! scores :a 2) ;; => {:a 2}
  (record-score! scores :b 3) ;; => {:a 2 :b 3}
  (record-score! scores :b 1) ;; => {:a 2 :b 3}
  (record-score! scores :a 4) ;; => {:a 4 :b 3}
  (user-high-score scores :a) ;; => 4
  (high-score scores)         ;; => [:a 4]
  )
