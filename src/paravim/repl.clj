(ns paravim.repl
  (:require [clojure.string :as str]
            [clojure.main])
  (:import [clojure.lang LineNumberingPushbackReader]
           [java.io PipedWriter PipedReader PrintWriter]))

(defn remove-returns [^String s]
  (str/escape s {\return ""}))

(def ^:const repl-buffer-size 32)

(defn pipe-into-console! [in-pipe callback]
  (let [ca (char-array repl-buffer-size)]
    (.start
      (Thread.
        (fn []
          (loop []
            (when-let [read (try (.read in-pipe ca)
                              (catch Exception _))]
              (when (pos? read)
                (let [s (remove-returns (String. ca 0 read))]
                  (callback s)
                  (Thread/sleep 100) ; prevent thread from being flooded
                  (recur))))))))))

(defn create-pipes []
  (let [out-pipe (PipedWriter.)
        in (LineNumberingPushbackReader. (PipedReader. out-pipe))
        pout (PipedWriter.)
        out (PrintWriter. pout)
        in-pipe (PipedReader. pout)]
    {:in in :out out :in-pipe in-pipe :out-pipe out-pipe}))

(defn start-repl-thread! [main-ns pipes callback]
  (let [{:keys [in-pipe in out]} pipes]
    (pipe-into-console! in-pipe callback)
    (.start
      (Thread.
        (fn []
          (binding [*out* out
                    *err* out
                    *in* in]
            (try
              (clojure.main/repl
                :init
                (fn []
                  (when main-ns
                    (doto main-ns require in-ns)))
                :read
                (fn [request-prompt request-exit]
                  (let [form (clojure.main/repl-read request-prompt request-exit)]
                    (if (= 'paravim.repl/exit form) request-exit form))))
              (catch Exception e (some-> (.getMessage e) println))
              (finally (println "=== Finished ==="))))))))
  pipes)

(defn reload-file! [buffer pipes current-tab]
  (let [{:keys [out-pipe]} pipes
        {:keys [lines file-name clojure?]} buffer]
    (when (and clojure? (= current-tab :files))
      (doto out-pipe
        (.write (str "(do "
                     (pr-str '(println))
                     (pr-str (list 'println "Reloading" file-name))
                     (str/join \newline lines)
                     ")\n"))
        .flush)
      true)))

