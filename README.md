# pumila

Lighter Clojure replacement for Netflix hystrix latency 
and fault tolerance library

## Features

Basically: it runs tasks.

- synchronous execution is provided by the `exec` function
- asynchronous execution is provided by the `queue` function
- each execution is mesured :
  - `queue duration` is the overhead time between submission
     and execution of the task.
  - `call duration` is the time took to purely execute the 
     task
  - mesurements are attached to the result as metadata.
- a hook (`error-fn`) lets specify a side-effect to execute in case of 
  task failure
- another one (`fallback-fn`) is used to provide a default 
  value in case of task failure 
- an execution timeout can be specified : the task is 
  guaranteed to take at most this amount of time, otherwise
  it will fail.
     
## Commander

A commander is an object that implements the `Commander` 
protocol. The above features are provided whatever the 
implementation of the Commmander.

A commander implements the task scheduling and the metrics.

There are two commanders provided :
- `pumila.commander.simple` is a no dependency commander
  that runs tasks out of a managed threadpool.
- `pumila.commander.reference` is a commander based on
[dirigiste][dirigiste-github] (a finely managed threadpool implementation),
and [metrics-clojure][metrics-clojure-github].


You can implement your own commander, for example based
on a ready made [ExecutorService][executor-javadoc] and micro-meter.


    
## Usage

```clojure
(defn get-my-uri
  [uri]
  (:body (http/get uri)))

;; this is a very basic commander, not suitable for production
;; you may want to use the one provided in pumila.commander.reference
(def commander (pmc/new-commander))

(let [;; async
      f (pm/queue commander {:timeout 1000 :error-fn println :fallback-fn (constantly "I feel lucky!")}
                  (get-my-uri "http://google.com"))
      result1 @f

      ;; sync
      result2 (pm/exec commander {:timeout 1000 :error-fn println :fallback-fn (constantly "I feel lucky!")} 
                       (get-my-uri "http://google.com"))])
```

## License

Copyright © 2016 Oscaro.com

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.


[metrics-clojure-github]: https://github.com/metrics-clojure/metrics-clojure
[dirigiste-github]: https://github.com/ztellman/dirigiste
[executor-javadoc]: https://docs.oracle.com/en/java/javase/12/docs/api/java.base/java/util/concurrent/Executors.html
