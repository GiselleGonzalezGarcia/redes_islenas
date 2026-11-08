(ns redes.client.db
  "Loads the static payload into an in-memory Datahike database and builds
  the lookup index used by search and tables."
  (:require [datahike.api :as d]
            [datahike.core :as dc]
            [cognitect.transit :as t]
            [clojure.string :as str]
            [cljs.core.async :as async :refer [<!]]
            [cljs.core.async.impl.protocols :as ap]
            [cljs.core.async.interop :refer [p->c]]
            [redes.shared.schema :as schema])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(def cfg {:store {:backend :memory :id #uuid "6f1d0c52-6a59-4c35-9d7e-3b2b8a1e0002"}
          :schema-flexibility :write
          :keep-history? false})

(defonce conn (atom nil))

(defn- chan? [x] (satisfies? ap/ReadPort x))

(defn- check [x] (if (instance? js/Error x) (throw x) x))

(defn fold
  "Lower-case and strip accents (same as the pipeline's fold)."
  [s]
  (when s
    (-> (.normalize (str s) "NFD")
        (str/replace #"[̀-ͯ]" "")
        str/lower-case
        (str/replace #"[^a-z0-9ñ]+" " ")
        str/trim)))

(defn- fetch-payload [url]
  (go (let [resp (<! (p->c (js/fetch url)))]
        (when-not (.-ok resp) (throw (js/Error. (str "payload " (.-status resp)))))
        (t/read (t/reader :json) (<! (p->c (.text resp)))))))

(defn index-from-cols
  "Rows (maps) from the precomputed column arrays of index.json."
  [{:keys [cols] :as idx}]
  (let [ks (keys cols)
        n (count (:id cols))]
    (assoc (dissoc idx :cols)
           :rows (persistent!
                  (reduce (fn [acc i] (conj! acc (into {} (map (fn [k] [k (nth (cols k) i)])) ks)))
                          (transient []) (range n))))))

(defn search [{:keys [rows]} q]
  (let [terms (remove str/blank? (str/split (or (fold q) "") #"\s+"))]
    (if (empty? terms)
      rows
      (filterv (fn [r] (every? #(str/includes? (:search r) %) terms)) rows))))

(def tx0 536870913)

(defn payload->db
  "Build the database value in one pass (datahike.core/init-db) from per-attribute
  [e v e v …] columns — no transactions."
  [{:keys [schema cols]}]
  (let [datoms (persistent!
                (reduce-kv (fn [acc a col]
                             (let [n (count col)]
                               (loop [i 0 acc acc]
                                 (if (< i n)
                                   (recur (+ i 2) (conj! acc (dc/datom (nth col i) a (nth col (inc i)) tx0)))
                                   acc))))
                           (transient []) cols))]
    ;; init-db validates schemas DataScript-style: only ref/tuple value types
    (dc/init-db datoms
                (into {} (map (fn [{:db/keys [ident valueType] :as a}]
                                [ident (cond-> (dissoc a :db/ident :db/doc)
                                         (not (#{:db.type/ref :db.type/tuple} valueType))
                                         (dissoc :db/valueType))]))
                      schema)
                {:schema-flexibility :read :keep-history? false})))

(defn load!
  "Two phases: index.json → (on-index index), then payload.json → (on-db db timings)."
  [base on-index on-db on-error]
  (go
    (try
      (let [t0 (js/performance.now)
            idx (index-from-cols (check (<! (fetch-payload (str base "/data/index.json")))))
            t1 (js/performance.now)
            _ (on-index idx)
            payload (check (<! (fetch-payload (str base "/data/payload.json"))))
            t2 (js/performance.now)
            db (payload->db payload)
            t3 (js/performance.now)]
        (on-db db {:index (- t1 t0) :payload (- t2 t1) :init-db (- t3 t2)}))
      (catch :default e (on-error e)))))
