(ns metabase.query-processor.middleware.add-query-throttle
  "Middleware that constraints the number of concurrent queries, rejects queries by throwing an exception and
  returning a 503 when we exceed our capacity"
  (:require [metabase.config :as config]
            [puppetlabs.i18n.core :refer [tru]])
  (:import [java.util.concurrent Semaphore TimeUnit]))

(defn- calculate-max-queries-from-max-threads []
  (let [max-threads (or (config/config-int :mb-jetty-maxthreads) 50)]
    (int (Math/ceil (/ max-threads 2)))))

(defn- ^Semaphore create-query-semaphore []
  (let [total-concurrent-queries (or (config/config-int :mb-max-concurrent-queries)
                                     (calculate-max-queries-from-max-threads))]
    (Semaphore. total-concurrent-queries true)))

(def ^Semaphore ^:private query-semaphore (create-query-semaphore))

(defn- throw-503-unavailable
  []
  (throw (ex-info (tru "Max concurrent query limit reached")
           {:status 503})))

;; Not marking this as `const` so it can be redef'd in tests
(def ^:private max-query-wait-time-in-seconds 5)

(defn- throttle-queries
  "Query middle that will throttle queries using `semaphore`. Throws 503 exceptions if there are no more slots
  available"
  [^Semaphore semaphore qp]
  (fn [query]
    ;; `tryAquire` will return `true` if it is able to get a permit, false otherwise
    (if (.tryAcquire semaphore max-query-wait-time-in-seconds TimeUnit/SECONDS)
      (try
        (qp query)
        (finally
          ;; We have a permit, whether the query is successful or it failed, we must make sure that we always release
          ;; the permit
          (.release semaphore)))
      ;; We were not able to get a permit without the timeout period, return a 503
      (throw-503-unavailable))))

(defn maybe-add-query-throttle
  "Adds the query throttle middleware as not as `MB_DISABLE_QUERY_THROTTLE` hasn't been set"
  [qp]
  (if (config/config-bool :mb-disable-query-throttle)
    qp
    (throttle-queries query-semaphore qp)))
