(ns redes.pipeline.core
  "Pipeline entry: workbook → Datahike (data/work/db) → browser payload."
  (:require [datahike.api :as d]
            [cognitect.transit :as t]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.data.csv :as csv]
            [redes.shared.schema :as schema]
            [redes.pipeline.xlsx :as xlsx]
            [redes.pipeline.gazetteer :as g]
            [redes.pipeline.build :as b]))

(def db-cfg {:store {:backend :file :path "data/work/db" :id #uuid "6f1d0c52-6a59-4c35-9d7e-3b2b8a1e0001"}
             :schema-flexibility :write
             :keep-history? true})

(def payload-path "resources/public/data/payload.json")

(defn- chunks [n xs] (partition-all n xs))

(defn load-db!
  "Recreate the database from the workbook and the gazetteer extracts."
  []
  (when (d/database-exists? db-cfg) (d/delete-database db-cfg))
  (d/create-database db-cfg)
  (let [conn (d/connect db-cfg)
        out (b/build (b/apply-corrections (xlsx/read-workbook)
                                          (edn/read-string (slurp "data/raw/corrections.edn")))
                     (g/load-wikidata "data/work/geo"))]
    (d/transact conn {:tx-data schema/schema})
    (println "schema ok")
    (d/transact conn {:tx-data (:sources out)})
    ;; places: parents before children
    (let [order {:island 0 :country 0 :region 0 :port 1 :municipality 1 :locality 2}]
      (doseq [batch (vals (sort (group-by #(order (:place/kind %)) (:places out))))]
        (d/transact conn {:tx-data batch})))
    (d/transact conn {:tx-data (:voyages out)})
    (doseq [c (chunks 2000 (:persons out))]
      (d/transact conn {:tx-data (vec c)})
      (print ".") (flush))
    [conn out]))

(def index-path "resources/public/data/index.json")

(defn export-index!
  "Precomputed search/table rows (column arrays) + headline figures."
  [{:keys [persons places voyages]}]
  (let [pl (into {} (map (juxt :place/id :place/name)) places)
        pname #(some-> % second pl)
        rows (->> persons
                  (map (fn [p]
                         {:id (:person/id p)
                          :name (:person/name p)
                          :surname (or (:person/surname1 p) "")
                          :origin (or (pname (:person/origin p)) (:person/origin-text p))
                          :residence (or (pname (:person/residence p)) (:person/residence-text p))
                          :island (some-> (:person/island p) second)
                          :dest (or (pname (:person/destination p)) (:person/destination-text p))
                          :year (:person/year p)
                          :age (:person/age p)
                          :search (str/join " " (remove nil? [(:person/search p)
                                                              (g/fold (pname (:person/origin p)))
                                                              (g/fold (pname (:person/residence p)))]))}))
                  (sort-by (juxt #(if (str/blank? (:surname %)) 1 0) #(g/fold (:surname %)) #(g/fold (:name %)))))
        ks [:id :name :origin :residence :island :dest :year :age :search]
        ys (filter #(<= 1400 % 1950) (keep :person/year persons))
        idx {:cols (into {} (map (fn [k] [k (mapv k rows)])) ks)
             :n-persons (count persons)
             :n-voyages (count voyages)
             :n-origins (count (into #{} (keep (comp second :person/origin)) persons))
             :years [(apply min ys) (apply max ys)]}]
    (with-open [o (io/output-stream index-path)]
      (t/write (t/writer o :json) idx))
    (count rows)))

(def ^:private ref-attrs
  (into #{} (comp (filter #(= :db.type/ref (:db/valueType %))) (map :db/ident)) schema/schema))

(defn- entities
  "All entities having attribute `a`, pulled, refs rewritten to their identity values."
  [db a id-attr-of]
  (->> (d/q [:find '[(pull ?e [*]) ...] :where ['?e a]] db)
       (mapv (fn [m]
               (into {}
                     (keep (fn [[k v]]
                             (cond
                               (= k :db/id) nil
                               (= k :person/events) [k (mapv #(dissoc % :db/id) v)]
                               (ref-attrs k) (let [ent (d/pull db '[*] (:db/id v))
                                                   ia (some #(when (contains? ent %) %) id-attr-of)]
                                               [k [ia (ent ia)]])
                               :else [k v])))
                     m)))))

(defn export-payload!
  [conn]
  (let [db (d/db conn)
        ids [:place/id :voyage/id :source/id :person/id]
        payload {:sources (entities db :source/id ids)
                 :places (entities db :place/id ids)
                 :voyages (entities db :voyage/id ids)
                 :persons (entities db :person/id ids)}]
    (io/make-parents payload-path)
    (with-open [o (io/output-stream payload-path)]
      (t/write (t/writer o :json) payload))
    (into {} (map (fn [[k v]] [k (count v)])) payload)))

(def user-nss #{"person" "place" "voyage" "source" "event"})

(defn export-datoms!
  "Payload for datahike.core/init-db in the browser: schema + per-attribute
  flat [e v e v …] columns (entity ids as in the canonical db)."
  [conn]
  (let [db (d/db conn)
        cols (reduce (fn [m dt]
                       (let [a (:a dt)]
                         (if (and (user-nss (namespace a))
                                  (not (#{:person/search} a)))   ; search lives in index.json
                           (update m a (fn [v] (-> (or v (transient [])) (conj! (:e dt)) (conj! (:v dt)))))
                           m)))
                     {} (d/datoms db :eavt))
        cols (into {} (map (fn [[a v]] [a (persistent! v)])) cols)
        payload {:schema schema/schema :cols cols}]
    (io/make-parents payload-path)
    (with-open [o (io/output-stream payload-path)]
      (t/write (t/writer o :json) payload))
    {:attrs (count cols) :datoms (reduce + (map #(quot (count %) 2) (vals cols)))}))

(def flows-path "resources/public/data/flows.json")

(defn export-flows!
  "Voyages (named ships), grouped departures without a ship, and home villages,
  for the maps. Every group/voyage carries the home places of its people."
  [{:keys [persons places voyages]}]
  (let [pl (into {} (map (juxt :place/id identity)) places)
        coord (fn [id] (when-let [p (pl id)] (when (:place/lat p) [(:place/lon p) (:place/lat p)])))
        home (fn [p] (some-> (:person/home p) second))
        dest-port (into {} (map (fn [[_ [id _ _ _ _ port]]] [id port])) g/destinations)
        by-voyage (group-by (comp second :person/voyage) (filter :person/voyage persons))
        vs (vec (for [v voyages
                      :let [to (second (:voyage/to v))
                            ps (by-voyage (:voyage/id v))]
                      :when to]
                  {:ship (:voyage/ship v) :year (:voyage/year v) :archive (name (:voyage/archive v))
                   :from (second (:voyage/from v)) :to to :n (count ps)
                   ;; [lon lat n] per home place of the passengers
                   :origins (->> ps (keep home) frequencies
                                 (keep (fn [[id n]] (when-let [[x y] (coord id)] [x y n]))) vec)}))
        groups (->> persons
                    (remove :person/voyage)
                    (keep (fn [p]
                            (let [arch (:person/archive p)
                                  dest (some-> (:person/destination p) second dest-port)]
                              (when (and arch dest (:person/year p))
                                [(:person/year p) (name arch) (g/archive->port arch) dest (home p)]))))
                    frequencies
                    (mapv (fn [[[y arch from to h] n]]
                            (cond-> {:year y :archive arch :from from :to to :n n}
                              (coord h) (assoc :origin (coord h))))))
        villages (->> persons (keep home) frequencies
                      (keep (fn [[id n]] (when-let [[x y] (coord id)] {:id id :name (:place/name (pl id)) :coord [x y] :n n})))
                      vec)
        port-ids (into #{} (mapcat (juxt :from :to)) (concat vs groups))
        out {:ports (into {} (map (fn [id] [id {:name (:place/name (pl id)) :coord (coord id)}])) port-ids)
             :voyages vs
             :groups groups
             :villages villages}]
    (with-open [o (io/output-stream flows-path)]
      (t/write (t/writer o :json) out))
    {:voyages (count vs) :groups (count groups) :villages (count villages)
     :people (reduce + (map :n (concat vs groups)))
     :with-home (+ (reduce + (map :n (filter :origin groups))) (reduce + (map #(nth % 2) (mapcat :origins vs))))}))

(def stats-path "resources/public/data/stats.json")

(defn- counts [f xs] (->> xs (keep f) frequencies (sort-by (comp str key)) (mapv (fn [[k n]] (assoc k :n n)))))

(defn export-stats!
  "Aggregates for the /datos charts (Teguise list unless stated)."
  [{:keys [persons places]}]
  (let [pl (into {} (map (juxt :place/id identity)) places)
        teg (filterv #(= "teguise" (second (:person/source %))) persons)
        island (fn [p] (case (some-> (:person/island p) second)
                         "tenerife" "tenerife" "gran-canaria" "gran-canaria" "la-palma" "la-palma"
                         nil "sin-dato" "otras"))
        decade #(some-> (:person/year %) (quot 10) (* 10))
        muni-of (fn [p] (let [o (some-> (:person/origin p) second pl)]
                          (case (:place/kind o)
                            :municipality o
                            :locality (some-> (:place/parent o) second pl)
                            nil)))
        dest (fn [p] (let [d (some-> (:person/destination p) second)]
                       (case d ("cuba" "venezuela" "puerto-rico" "uruguay") d nil nil "otros")))
        ys (fn [p] (when-let [y (:person/year p)] (when (<= 1840 y 1900) y)))]
    (let [out
          {:by-year-island (counts #(when-let [y (ys %)] {:year y :island (island %)}) teg)
           :pyramid (counts #(when (and (:person/sex %) (:person/age %) (< (:person/age %) 90))
                               {:sex (name (:person/sex %)) :age (* 5 (quot (:person/age %) 5))}) teg)
           :literacy (counts #(when (and (some? (:person/signed %)) (:person/sex %) (ys %))
                                {:decade (decade %) :sex (name (:person/sex %)) :signed (:person/signed %)}) teg)
           :dest-decade (counts #(when (and (ys %) (dest %)) {:decade (decade %) :dest (dest %)}) teg)
           :reclamo (counts #(when-let [k (:person/reclamo %)] {:kin k}) teg)
           :origins (->> teg (keep muni-of) frequencies (sort-by val >) (take 25)
                         (mapv (fn [[m n]] {:name (:place/name m) :island (some-> (:place/parent m) second) :n n})))
           :group (counts #(when-let [g (:person/group-size %)] {:size (min g 8)}) teg)
           :letters (counts #(when-let [s (or (:person/surname1 %) (:person/name-raw %))]
                               (let [c (-> (g/fold s) (subs 0 (min 1 (count (g/fold s)))) str/upper-case)]
                                 (when (re-matches #"[A-Z]" c) {:letter c}))) teg)
           :ships (->> teg (filter :person/voyage)
                       (group-by #(first (str/split (second (:person/voyage %)) #"\|")))
                       (map (fn [[ship ps]] {:ship ship :n (count ps)
                                             :years (vec (sort (distinct (keep :person/year ps))))}))
                       (sort-by :n >) (take 15) vec)
           :totals {:teguise (count teg) :all (count persons)}}]
      (with-open [o (io/output-stream stats-path)]
        (t/write (t/writer o :json) out))
      (into {} (map (fn [[k v]] [k (if (coll? v) (count v) v)])) out))))

(def csv-path "resources/public/data/redes-islenas.csv")

(defn export-csv!
  "All records as one CSV (UTF-8 with BOM so spreadsheets open accents correctly)."
  [{:keys [persons places]}]
  (let [pl (into {} (map (juxt :place/id identity)) places)
        pn #(some-> % second pl :place/name)
        cols [["id" :person/id] ["numero_fuente" :person/no] ["nombre" :person/given] ["primer_apellido" :person/surname1]
              ["segundo_apellido" :person/surname2] ["nombre_impreso" :person/name-raw] ["sexo" (comp {:m "hombre" :f "mujer"} :person/sex)]
              ["estado_civil" (comp {:single "soltero/a" :married "casado/a" :widowed "viudo/a"} :person/civil)] ["edad" :person/age] ["anio_nacimiento_aprox" :person/birth-year]
              ["firma" (comp #(case % true "si" false "no" nil) :person/signed)] ["oficio" :person/occupation]
              ["natural_de" :person/origin-text] ["lugar_nacimiento" (comp pn :person/origin)]
              ["vecino_de" :person/residence-text] ["lugar_residencia" (comp pn :person/residence)]
              ["isla" (comp pn :person/island)] ["destino" :person/destination-text] ["anio" :person/year]
              ["barco" (comp #(some-> % second (str/split #"\|") first) :person/voyage)]
              ["reclamo" :person/reclamo] ["cuno" :person/stamp] ["viajan_juntos" :person/group-size]
              ["observaciones" :person/remarks] ["registrado_en" (comp #(some-> % name) :person/archive)]
              ["conyuge" :person/spouse] ["padre" :person/father] ["madre" :person/mother] ["hijos" :person/children]
              ["lecturas_dudosas" (comp #(some->> % seq (str/join "; ")) :person/doubtful)]
              ["fuente" :person/citation]]]
    (with-open [w (io/writer csv-path :encoding "UTF-8")]
      (.write w "\uFEFF")
      (csv/write-csv w (cons (map first cols)
                             (for [p (sort-by :person/id persons)]
                               (map (fn [[_ f]] (str (or (f p) ""))) cols)))))
    (count persons)))

(defn -main [& _]
  (let [t0 (System/currentTimeMillis)
        [conn out] (load-db!)]
    (println "db loaded" (- (System/currentTimeMillis) t0) "ms")
    (println "payload" (export-datoms! conn))
    (println "index rows" (export-index! out))
    (println "flows" (export-flows! out))
    (println "stats" (export-stats! out))
    (println "csv" (export-csv! out))
    (shutdown-agents)
    (System/exit 0)))
