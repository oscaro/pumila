(ns pumila.core
  (:require [metrics.timers :as tmr])
  (:import [io.aleph.dirigiste Executors Stats$Metric]
           [java.util.concurrent Callable ExecutorService
            ScheduledExecutorService ScheduledThreadPoolExecutor]
           [java.util EnumSet]))

(defrecord Commander [label options executor scheduler])

(defn make-commander
  ([{:keys [label timeout] :as options} executor]
   (let [scheduler (ScheduledThreadPoolExecutor. 0)]
     (.setMaximumPoolSize scheduler 1)
     (.setRemoveOnCancelPolicy scheduler true)
     (->Commander label options executor scheduler)))
  ([{:keys [label timeout utilization max-size all-stats]
     :or {utilization 0.8 max-size 10}
     :as opts}]
   (if all-stats
     (make-commander opts (Executors/utilizationExecutor utilization (int max-size)))
     (make-commander opts (Executors/utilizationExecutor utilization (int max-size) (EnumSet/allOf Stats$Metric)))))
  ([] (make-commander {})))

(defn close
  [{:keys [executor scheduler] :as commander}]
  (.shutdown scheduler)
  (.shutdown executor)
  commander)

(defn add-meta
  [v m]
  (if (and m (instance? clojure.lang.IMeta v))
    (with-meta v m)
    v))

(defn ms
  [v]
  (when v (* 1000 v)))

(defn metric-name
  [metric nam]
  (if (string? metric)
    (str metric "-" nam)
    (conj (into [] (take 2 metric)) "queue-duration")))

(defn queue*
  [commander
   {:keys [timeout fallback-fn error-fn timeout-val metric] :as options
    :or {timeout-val ::timeout}}
   run-fn args]
  (let [^ExecutorService executor (:executor commander)
        ^ScheduledExecutorService scheduler (:scheduler commander)
        result (promise)
        queue-duration-atom (when metric (promise))
        call-duration-atom (when metric (promise))
        queue-timer (when metric (tmr/start (tmr/timer (metric-name metric "queue-duration"))))
        ^Runnable task #(let [res (let [queue-latency (when metric (tmr/stop queue-timer))
                                        call-timer (when metric
                                                     (tmr/start (tmr/timer
                                                                 (metric-name metric "call-duration"))))]
                                    (when metric
                                      (deliver queue-duration-atom (ms queue-latency)))
                                    (try
                                      (let [call-result (apply run-fn args)
                                            latency (when call-timer (tmr/stop call-timer))]
                                        (when metric
                                          (deliver call-duration-atom (ms latency)))
                                        call-result)
                                      (catch Exception e
                                        (let [exi (ex-info "Error calling command"
                                                           {:commander (:label commander) :args args
                                                            :type :error :options options} e)]
                                          (when-let [exp (::exp options)] (deliver exi e))
                                          (when metric
                                            (deliver queue-duration-atom (ms queue-latency)))
                                          (try (when error-fn (error-fn exi)) (catch Exception _ nil))
                                          (when fallback-fn
                                            (try
                                              (let [fallback-res (apply fallback-fn args)
                                                    latency (when call-timer (tmr/stop call-timer))]
                                                (when metric
                                                  (deliver call-duration-atom (ms latency)))
                                                fallback-res)
                                              (catch Exception _ nil)))))))]
                          (deliver result res))
        ^Runnable timeout-task (when timeout
                                 #(when  (not (realized? result))
                                    (when error-fn
                                      (let [e (ex-info "Timeout with queue asynchronous call"
                                                       {:commander (:label commander) :args args
                                                        :timeout timeout :type :timeout :options options})]
                                        (error-fn e)))
                                    (if fallback-fn
                                      (try
                                        (when metric
                                          (deliver result (apply fallback-fn args)))
                                        (catch Exception _ (deliver result timeout-val)))
                                      (deliver result timeout-val))))]
    (.submit executor task)
    (when timeout-task
      (.schedule scheduler timeout-task timeout java.util.concurrent.TimeUnit/MILLISECONDS))
    (if metric
      (with-meta result
        {:queue-duration queue-duration-atom :call-duration call-duration-atom})
      result)))

(defn exec*
  [commander
   {:keys [timeout fallback-fn error-fn] :as options}
   run-fn args]
  (let [exp (promise)
        opts (-> options
                 (assoc ::exp exp)
                 (dissoc :timeout))
        p (queue* commander opts run-fn args)
        result (deref p timeout ::timeout)
        queue-duration (some-> p (meta) (:queue-duration) (deref 1 nil))
        call-duration (some-> p (meta) (:call-duration) (deref 1 nil))]
    (cond
      (realized? exp) (throw @exp)
      (= result ::timeout) (let [e (ex-info "Timeout with exec synchronous call"
                                            {:commander (:label commander) :args args
                                             :timeout timeout :type :timeout :options options})]
                             (when error-fn (error-fn e))
                             (if fallback-fn
                               (add-meta
                                (apply fallback-fn args)
                                {:queue-duration queue-duration})
                               (throw e)))
      :else (add-meta result
                      {:queue-duration queue-duration
                       :call-duration call-duration}))))

(defmacro queue
  ([commander options [run-fn & args]]
   `(queue* ~commander ~options ~run-fn (vector ~@args)))
  ([commander body]
   `(queue ~commander {} ~body)))

(defmacro exec
  ([commander options [run-fn & args]]
   `(exec* ~commander ~options ~run-fn (vector ~@args)))
  ([commander body]
   `(exec ~commander {} ~body)))
