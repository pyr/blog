(ns redis.transient
  (:refer-clojure :exclude [read-string])
  (:require [clojure.edn :refer [read-string]])
  (:require [taoensso.carmine :as r :refer [wcar]]))

(defn ->mapentry
  [k v]
  ^{:type :redis :prefix "[" :suffix "]"}
  (reify
    clojure.lang.Indexed
    (nth [this i] (nth [k v] i))
    (nth [this i default] (nth [k v] i default))
    clojure.lang.Seqable
    (seq [this] (list k v))
    clojure.lang.Counted
    (count [this] 2)
    clojure.lang.IMapEntry
    (getKey [this] k)
    (getValue [this] v)
    (key [this] k)
    (val [this] v)))

(defn hash->transient
  [spec k]
  ^{:type :redis :prefix "{" :suffix "}" :sep "," :tuple? true}
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
    clojure.lang.IFn
    (invoke [this subk]
      (.valAt this subk))
    clojure.lang.Counted
    (count [this]
      (count (partition 2 (wcar spec (r/hgetall k)))))
    clojure.lang.Seqable
    (seq [this]
      (for [[k v] (partition 2 (wcar spec (r/hgetall k)))]
        (->mapentry (read-string k)
                    (read-string v))))))

(defn list->transient
  [spec k]
  ^{:type :redis :prefix "[" :suffix "]"}
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

(defn set->transient
  [spec k]
  ^{:type :redis :prefix "#{" :suffix "}"}
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
    clojure.lang.IFn
    (invoke [this member]
      (when (.contains this member)
        member))
    (contains [this v]
      (let [member (wcar spec (r/sismember k (pr-str v)))]
        (pos? member)))
    (get [this v]
      (when (.contains this v)
        v))))

(defn instance->transient
  [spec]
  ^{:type :redis :prefix "{" :suffix "}" :sep "," :tuple? true}
  (reify
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
    (valAt [this k default]
      (or (.valAt this k) default))
    clojure.lang.Counted
    (count [this]
      (count (wcar spec (r/keys "*"))))
    clojure.lang.Seqable
    (seq [this]
      (let [keys (wcar spec (r/keys "*"))]
        (for [k keys]
          (->mapentry (read-string k) (.valAt this (read-string k))))))
    clojure.lang.ITransientMap
    (assoc [this k v]
      (let [k (pr-str k)]
        (cond
         (set? v)        (doseq [member v]
                           (wcar spec (r/sadd k (pr-str member))))
         (map? v)        (doseq [[subk v] v]
                           (wcar spec (r/hset k (pr-str subk) (pr-str v))))
         (sequential? v) (doseq [e v]
                           (wcar spec (r/lpush k (pr-str e))))
         :else           (wcar spec (r/set k (pr-str v)))))
      this)
    (without [this k]
      (wcar spec (r/del (pr-str k)))
      this)))

(defmethod print-method :redis
  [obj ^java.io.Writer writer]
  (let [{:keys [prefix suffix sep tuple?]} (meta obj)]
    (.write writer prefix)
    (when (pos? (count obj))
      (loop [[item & items] (seq obj)]
        (if tuple?
          (do
            (print-method (key item) writer)
            (.write writer " ")
            (print-method (val item) writer))
          (print-method item writer))
        (when (seq items)
          (.write writer (str sep  " "))
          (recur items))))
(.write writer suffix)))
