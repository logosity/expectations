(ns expectations
  (:use clojure.set)
  (:require clojure.template))

;;; GLOBALS
(def run-tests-on-shutdown (atom true))

(def *test-name* "test name unset")
(def *test-meta* {})

(def *report-counters* nil) ; bound to a ref of a map in test-ns

(def *initial-report-counters* ; used to initialize *report-counters*
  {:test 0, :pass 0, :fail 0, :error 0})

;;; UTILITIES FOR REPORTING FUNCTIONS

(defn stack->file&line [ex index]
  (let [s (nth (.getStackTrace ex) index)]
    (str (.getFileName s) ":" (.getLineNumber s))))

(defn inc-report-counter [name]
  (when *report-counters*
    (dosync (commute *report-counters* assoc name
      (inc (or (*report-counters* name) 0))))))

;;; TEST RESULT REPORTING
(defn str-join [separator coll]
  (apply str (interpose separator (remove nil? coll))))

(defn test-name [{:keys [line ns]}]
  (str ns ":" line))

(defn test-file [{:keys [file line]}]
  (str (last (re-seq #"[A-Za-z_\.]+" file)) ":" line))

(defn raw-str [[e a]]
  (if (or (= ::true e) (= ":expectations/true" e))
    (str "(expect " a ")")
    (str "(expect " e " " a ")")))

(defn fail [test-name test-meta msg] (println (str "\nfailure in (" (test-file test-meta) ") : " (:ns test-meta))) (println msg))
(defn summary [msg] (println msg))
(defn started [test-name test-meta])
(defn finished [test-name test-meta])

(defn ignored-fns [{:keys [className fileName]}]
  (or (= fileName "expectations.clj")
    (re-seq #"clojure.lang" className)
    (re-seq #"clojure.core" className)
    (re-seq #"clojure.main" className)
    (re-seq #"java.lang" className)))

(defn pruned-stack-trace [t]
  (str-join "\n"
    (map (fn [{:keys [className methodName fileName lineNumber]}]
      (str "           " className " (" fileName ":" lineNumber ")"))
      (remove ignored-fns (map bean (.getStackTrace t))))))

(defmulti report :type)

(defmethod report :pass [m]
  (inc-report-counter :pass))

(defmethod report :fail [m]
  (inc-report-counter :fail)
  (fail *test-name* *test-meta*
    (str-join "\n"
      [(when-let [msg (:raw m)] (str "      raw: " (raw-str msg)))
       (when-let [msg (:result m)] (str "   result: " (str-join " " msg)))
       (when-let [msg (:expected-message m)] (str "  exp-msg: " msg))
       (when-let [msg (:actual-message m)] (str "  act-msg: " msg))
       (when-let [msg (:message m)] (str "  message: " msg))])))

(defmethod report :error [{:keys [result raw] :as m}]
  (inc-report-counter :error)
  (fail *test-name* *test-meta*
    (str-join "\n"
      [(when raw (str "      raw: " (raw-str raw)))
       (when-let [msg (:expected-message m)] (str "  exp-msg: " msg))
       (when-let [msg (:actual-message m)] (str "  act-msg: " msg))
       (str "    threw: " (class result) "-" (.getMessage result))
       (pruned-stack-trace result)])))

(defmethod report :summary [m]
  (summary (str "\nRan " (:test m) " tests containing "
    (+ (:pass m) (:fail m) (:error m)) " assertions.\n"
    (:fail m) " failures, " (:error m) " errors.")))

;; TEST RUNNING

(defn disable-run-on-shutdown [] (reset! run-tests-on-shutdown false))

(defn test-var [v]
  (when-let [t (var-get v)]
    (let [tn (test-name (meta v))
          tm (meta v)]
      (started tn tm)
      (inc-report-counter :test)
      (binding [*test-name* tn
                *test-meta* tm]
        (t))
      (finished tn tm))))

(defn test-vars [vars]
  (binding [*report-counters* (ref *initial-report-counters*)]
    (doseq [v vars] (test-var v))
    @*report-counters*))

(defn run-tests-in-vars [vars]
  (let [summary (assoc (test-vars vars) :type :summary)]
    (report summary)
    summary))

(defn sort-by-str [vs]
  (sort #(.compareTo (str %1) (str %2)) vs))

(defn test-ns [ns]
  (test-vars (filter #(:expectation (meta %)) (-> ns ns-interns vals sort-by-str))))

(defn run-tests [namespaces]
  (let [summary (assoc (apply merge-with + (map test-ns namespaces)) :type :summary)]
    (report summary)
    summary))

(defn run-all-tests
  ([] (run-tests (all-ns)))
  ([re] (run-tests (filter #(re-matches re (name (ns-name %))) (all-ns)))))

(defmulti nan->keyword class :default :default)

(defmethod nan->keyword java.util.Map [m]
  (let [f (fn [[k v]] [k (if (and (number? v) (Double/isNaN v)) :DoubleNaN v)])]
    (clojure.walk/postwalk (fn [x] (if (map? x) (into {} (map f x)) x)) m)))

(defmethod nan->keyword java.util.List [m]
  (map #(if (and (number? %) (Double/isNaN %)) :DoubleNaN %) m))

(defmethod nan->keyword :default [m]
  (if (and (number? m) (Double/isNaN m)) :DoubleNaN m))

(defmulti extended-not= (fn [x y] [(class x) (class y)]) :default :default)

(defmethod extended-not= [Double Double] [x y]
  (if (and (Double/isNaN x) (Double/isNaN y))
    false
    (not= x y)))

(defmethod extended-not= :default [x y] (not= x y))

(defn map-intersection [e a]
  (let [in-both (intersection (set (keys e)) (set (keys a)))]
    (select-keys (merge-with vector e a) in-both)))

(defn ->disagreement [prefix [k [v1 v2]]]
  (if (and (map? v1) (map? v2))
    (str-join
      "\n           "
      (remove nil? (map (partial ->disagreement (str (when prefix (str prefix " {")) k)) (map-intersection v1 v2))))
    (when (extended-not= v1 v2)
      (str (when prefix (str prefix " {")) (pr-str k) " expected " (pr-str v1) " but was " (pr-str v2)))))

(defn map-diff-message [e a padding]
  (->>
    (map (partial ->disagreement nil) (map-intersection e a))
    (remove nil?)
    (remove empty?)
    seq))

(defn normalize-keys* [a ks m]
  (if (map? m)
    (reduce into [] (map (fn [[k v]] (normalize-keys* a (conj ks k) v)) (seq m)))
    (conj a ks)))

(defn normalize-keys [m] (normalize-keys* [] [] m))

(defn ->missing-message [msg item]
  (str (str-join " {" item) msg))

(defn map-difference [e a]
  (difference (set (normalize-keys e)) (set (normalize-keys a))))

(defn map-missing-message [e a msg]
  (->>
    (map-difference e a)
    (map (partial ->missing-message msg))
    seq))

(defn map-compare [e a str-e str-a original-a]
  (if (= (nan->keyword e) (nan->keyword a))
    (report {:type :pass})
    (report {:type :fail
             :actual-message
             (when-let [messages (map-missing-message e a " is in expected, but not in actual")]
               (str-join "\n           " messages))
             :expected-message
             (when-let [messages (map-missing-message a e " is in actual, but not in expected")]
               (str-join "\n           " messages))
             :raw [str-e str-a]
             :result [e "are not in" original-a]
             :message (when-let [messages (map-diff-message e a "")] (str-join "\n           " messages))})))

(defmulti compare-expr (fn [e a str-e str-a]
  (cond
    (isa? e Throwable) ::expect-exception
    (instance? Throwable e) ::expected-exception
    (instance? Throwable a) ::actual-exception
    (fn? e) ::fn
    (= ::true e) ::true
    (::in-flag a) ::in
    :default [(class e) (class a)])))

(defmethod compare-expr :default [e a str-e str-a]
  (if (= e a)
    (report {:type :pass})
    (report {:type :fail :raw [str-e str-a]
             :result [(pr-str e) "does not equal" (pr-str a)]})))

(defmethod compare-expr ::fn [e a str-e str-a]
  (if (e a)
    (report {:type :pass})
    (report {:type :fail :raw [str-e str-a]
             :result [(pr-str a) "is not" str-e]})))

(defmethod compare-expr ::true [e a str-e str-a]
  (if a
    (report {:type :pass})
    (report {:type :fail :raw [::true str-a]
             :result [(pr-str a)]})))

(defmethod compare-expr ::in [e a str-e str-a]
  (cond
    (instance? java.util.List (::in a))
    (if (seq (filter (fn [item] (= (nan->keyword e) (nan->keyword item))) (::in a)))
      (report {:type :pass})
      (report {:type :fail :raw [str-e str-a]
               :result ["value" (pr-str e) "not found in" (::in a)]}))
    (instance? java.util.Set (::in a))
    (if ((::in a) e)
      (report {:type :pass})
      (report {:type :fail :raw [str-e str-a]
               :result ["key" (pr-str e) "not found in" (::in a)]}))
    (instance? java.util.Map (::in a))
    (map-compare e (select-keys (::in a) (keys e)) str-e str-a (::in a))
    :default (report {:type :fail :raw [str-e str-a]
                      :result [(pr-str (::in a))]
                      :message "You must supply a list, set, or map when using (in)"})))

(defmethod compare-expr [Class Object] [e a str-e str-a]
  (if (instance? e a)
    (report {:type :pass})
    (report {:type :fail :raw [str-e str-a]
             :result [a "is not an instance of" e]})))


(defmethod compare-expr ::actual-exception [e a str-e str-a]
  (report {:type :error
           :raw [str-e str-a]
           :actual-message (str "exception in actual: " str-a)
           :result a}))

(defmethod compare-expr ::expected-exception [e a str-e str-a]
  (report {:type :error
           :raw [str-e str-a]
           :expected-message (str "exception in expected: " str-e)
           :result e}))

(defmethod compare-expr [java.util.regex.Pattern Object] [e a str-e str-a]
  (if (re-seq e a)
    (report {:type :pass})
    (report {:type :fail,
             :raw [str-e str-a]
             :result ["regex" (pr-str e) "not found in" (pr-str a)]})))

(defmethod compare-expr ::expect-exception [e a str-e str-a]
  (if (instance? e a)
    (report {:type :pass})
    (report {:type :fail :raw [str-e str-a]
             :result [str-a "did not throw" str-e]})))

(defmethod compare-expr [java.util.Map java.util.Map] [e a str-e str-a]
  (map-compare e a str-e str-a a))

(defmethod compare-expr [java.util.Set java.util.Set] [e a str-e str-a]
  (if (= e a)
    (report {:type :pass})
    (let [diff-fn (fn [e a] (seq (difference e a)))]
      (report {:type :fail
               :actual-message (when-let [v (diff-fn e a)]
                 (str (str-join ", " v) " are in expected, but not in actual"))
               :expected-message (when-let [v (diff-fn a e)]
                 (str (str-join ", " v) " are in actual, but not in expected"))
               :raw [str-e str-a]
               :result [e "does not equal" (pr-str a)]}))))

(defmethod compare-expr [java.util.List java.util.List] [e a str-e str-a]
  (if (= (nan->keyword e) (nan->keyword a))
    (report {:type :pass})
    (let [diff-fn (fn [e a] (seq (difference (set e) (set a))))]
      (report {:type :fail
               :actual-message (when-let [v (diff-fn e a)]
                 (str (str-join ", " v) " are in expected, but not in actual"))
               :expected-message (when-let [v (diff-fn a e)]
                 (str (str-join ", " v) " are in actual, but not in expected"))
               :raw [str-e str-a]
               :result [e "does not equal" (pr-str a)]
               :message (cond
                 (and
                   (= (set e) (set a))
                   (= (count e) (count a))
                   (= (count e) (count (set a))))
                 "lists appears to contain the same items with different ordering"
                 (and (= (set e) (set a)) (< (count e) (count a)))
                 "some duplicate items in actual are not expected"
                 (and (= (set e) (set a)) (> (count e) (count a)))
                 "some duplicate items in expected are not actual"
                 (< (count e) (count a))
                 "actual is larger than expected"
                 (> (count e) (count a))
                 "expected is larger than actual")}))))

(defmacro doexpect [e a]
  `(let [e# (try ~e (catch Throwable t# t#))
         a# (try ~a (catch Throwable t# t#))]
    (compare-expr e# a# ~(str e) ~(str a))))

(defmacro expect [e a]
  `(def ~(vary-meta (gensym) assoc :expectation true)
    (fn [] (doexpect ~e ~a))))

(defmacro expect-focused [e a]
  `(def ~(vary-meta (gensym) assoc :expectation true :focused true)
    (fn [] (doexpect ~e ~a))))

(defmacro given [bindings form & args]
  (if args
    `(clojure.template/do-template ~bindings ~form ~@args)
    `(clojure.template/do-template [~'x ~'y] ~(list 'expect 'y (list 'x bindings)) ~@(rest form))))

(defn in [n] {::in n ::in-flag true})

(->
  (Runtime/getRuntime)
  (.addShutdownHook
    (proxy [Thread] []
      (run [] (when @run-tests-on-shutdown (run-all-tests))))))