; -*- mode: clojure; -*-
; vim: filetype=clojure

(logging/init)
(instrumentation {:enabled? false})
(udp-server)
(tcp-server)
(periodically-expire 1)

(let [store    (index)
      trending (top 10 :metric (tag "top" store) store)]
  (streams
    (by :service (moving-time-window 3600 (smap folds/sum trending)))))
