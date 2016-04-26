(ns pumila.core
  (:import [io.aleph.dirigiste Executors]
           [java.util.concurrent Callable ExecutorService
            ScheduledExecutorService ScheduledThreadPoolExecutor]))

(defrecord Commander [label options executor scheduler])

(defn make-commander
  ([{:keys [label timeout] :as options} executor]
   (let [scheduler (ScheduledThreadPoolExecutor. 0)]
     (.setMaximumPoolSize scheduler 1)
     (.setRemoveOnCancelPolicy scheduler true)
     (->Commander label options executor scheduler)))
  ([{:keys [label timeout utilization max-size]
     :or {utilization 0.8 max-size 10}
     :as opts}]
   (make-commander opts (Executors/utilizationExecutor utilization max-size)))
  ([] (make-commander {})))

(defn close
  [{:keys [executor scheduler] :as commander}]
  (.shutdown scheduler)
  (.shutdown executor)
  commander)

(defn queue*
  [commander
   {:keys [timeout fallback-fn error-fn] :as options}
   run-fn args]
  (let [^ExecutorService executor (:executor commander)
        ^ScheduledExecutorService scheduler (:scheduler commander)
        result (promise)
        ^Runnable task #(let [res (try
                                    (apply run-fn args)
                                    (catch Exception e
                                      (let [exi (ex-info "Error calling command"
                                                         {:commander (:label commander) :args args
                                                          :type :error :options options})]
                                        (when-let [exp (::exp options)] (deliver exi e))
                                        (try (when error-fn (error-fn exi)) (catch Exception _ nil))
                                        (when fallback-fn (try
                                                            (apply fallback-fn args)
                                                            (catch Exception _ nil))))))]
                          (deliver result res))
        ^Runnable timeout-task (when timeout
                                 #(do (when error-fn
                                        (let [e (ex-info "Timeout with queue asynchronous call"
                                                         {:commander (:label commander) :args args
                                                          :timeout timeout :type :timeout :options options})]
                                          (error-fn e)))
                                      (if fallback-fn
                                        (try
                                          (deliver result (apply fallback-fn args))
                                          (catch Exception _ (deliver result ::timeout)))
                                        (deliver result ::timeout))))]
    (.submit executor task)
    (when timeout-task (.schedule scheduler timeout-task timeout java.util.concurrent.TimeUnit/MILLISECONDS))
    result))

(defn exec*
  [commander
   {:keys [timeout fallback-fn error-fn] :as options}
   run-fn args]
  (let [exp (promise)
        opts (-> options
                 (assoc ::exp exp)
                 (dissoc :timeout))
        p (queue* commander opts run-fn args)
        result (deref p timeout ::timeout)]
    (cond
      (realized? exp) (throw @exp)
      (= result ::timeout) (let [e (ex-info "Timeout with exec synchronous call"
                                            {:commander (:label commander) :args args
                                             :timeout timeout :type :timeout :options options})]
                             (when error-fn (error-fn e args))
                             (if fallback-fn
                               (apply fallback-fn args)
                               (throw e)))
      :else result)))

(defmacro queue
  ([commander options [run-fn & args]]
   `(queue* ~commander ~options ~run-fn ~args))
  ([commander body]
   `(queue ~commander {} ~body)))

(defmacro exec
  ([commander options [run-fn & args]]
   `(exec* ~commander ~options ~run-fn ~args))
  ([commander body]
   `(exec ~commander {} ~body)))
