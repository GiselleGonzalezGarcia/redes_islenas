(ns redes.pipeline.gazetteer
  "Places: Canarian municipalities/localities (Wikidata, CC0), islands, ports and
  destinations. Resolves the place strings of the workbook to place ids."
  (:require [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.text Normalizer Normalizer$Form]))

(defn fold
  "Lower-case, strip accents, collapse non-alphanumerics to single spaces."
  [s]
  (when s
    (-> (Normalizer/normalize (str s) Normalizer$Form/NFD)
        (str/replace #"\p{M}" "")
        str/lower-case
        (str/replace #"[^a-z0-9ñ]+" " ")
        str/trim)))

(defn slug [s] (str/replace (fold s) " " "-"))

;; ---------------------------------------------------------------------------
;; Fixed places

(def islands
  ;; id name lat lon bbox [lon0 lat0 lon1 lat1]
  [["la-palma" "La Palma" 28.68 -17.85 [-18.02 28.44 -17.70 28.87]]
   ["el-hierro" "El Hierro" 27.74 -18.02 [-18.18 27.62 -17.87 27.87]]
   ["la-gomera" "La Gomera" 28.10 -17.23 [-17.36 28.00 -17.08 28.23]]
   ["tenerife" "Tenerife" 28.27 -16.60 [-16.94 27.98 -16.10 28.61]]
   ["gran-canaria" "Gran Canaria" 27.96 -15.60 [-15.85 27.72 -15.34 28.20]]
   ["fuerteventura" "Fuerteventura" 28.36 -14.05 [-14.53 28.03 -13.81 28.79]]
   ["lanzarote" "Lanzarote" 29.03 -13.63 [-13.89 28.80 -13.39 29.45]]])

(defn island-of [lat lon]
  (some (fn [[id _ _ _ [x0 y0 x1 y1]]]
          (when (and (<= x0 lon x1) (<= y0 lat y1)) id))
        islands))

(def ports
  ;; id name lat lon parent
  [["port-sc-tenerife" "Santa Cruz de Tenerife" 28.4636 -16.2518 "tenerife"]
   ["port-las-palmas" "Las Palmas de Gran Canaria" 28.1000 -15.4150 "gran-canaria"]
   ["port-sc-la-palma" "Santa Cruz de La Palma" 28.6835 -17.7642 "la-palma"]
   ["port-la-habana" "La Habana" 23.1416 -82.3534 "cuba"]
   ["port-la-guaira" "La Guaira" 10.6036 -66.9333 "venezuela"]
   ["port-san-juan" "San Juan" 18.4655 -66.1057 "puerto-rico"]
   ["port-montevideo" "Montevideo" -34.9060 -56.2130 "uruguay"]
   ["port-buenos-aires" "Buenos Aires" -34.6037 -58.3816 "argentina"]
   ["port-veracruz" "Veracruz" 19.1738 -96.1342 "mexico"]
   ["port-limon" "Puerto Limón" 9.9907 -83.0360 "costa-rica"]
   ["port-rio" "Río de Janeiro" -22.9068 -43.1729 "brasil"]
   ["port-mogador" "Mogador (Esauira)" 31.5085 -9.7595 "mogador"]])

(def archive->port
  {:la-palma "port-sc-la-palma" :tenerife "port-sc-tenerife" :gran-canaria "port-las-palmas"})

(def destinations
  ;; workbook value → [id name kind lat lon port-id]; lat/lon = main port (nil: no coordinates)
  {"Cuba"               ["cuba" "Cuba" :country 23.1416 -82.3534 "port-la-habana"]
   "Venezuela"          ["venezuela" "Venezuela" :country 10.6036 -66.9333 "port-la-guaira"]
   "Puerto Rico"        ["puerto-rico" "Puerto Rico" :country 18.4655 -66.1057 "port-san-juan"]
   "Uruguay"            ["uruguay" "Uruguay" :country -34.9060 -56.2130 "port-montevideo"]
   "Argentina"          ["argentina" "Argentina" :country -34.6037 -58.3816 "port-buenos-aires"]
   "México"             ["mexico" "México" :country 19.1738 -96.1342 "port-veracruz"]
   "Costa Rica"         ["costa-rica" "Costa Rica" :country 9.9907 -83.0360 "port-limon"]
   "Brasil"             ["brasil" "Brasil" :country -22.9068 -43.1729 "port-rio"]
   "Mogador"            ["mogador" "Mogador (Marruecos)" :region 31.5085 -9.7595 "port-mogador"]
   "Inglaterra"         ["inglaterra" "Inglaterra" :country 51.5072 -0.1276 nil]
   "Francia"            ["francia" "Francia" :country 46.6 2.2 nil]
   "Antillas españolas" ["antillas" "Antillas españolas" :region nil nil nil]
   "América"            ["america" "América (sin especificar)" :region nil nil nil]
   "Ultramar"           ["ultramar" "Ultramar (sin especificar)" :region nil nil nil]
   "Sin destino"        ["sin-destino" "Sin destino" :region nil nil nil]})

;; ---------------------------------------------------------------------------
;; Wikidata extracts (data/work/geo/*.csv)

(defn- read-csv [path]
  (with-open [r (io/reader path)]
    (let [[h & rows] (doall (csv/read-csv r))
          ks (map keyword h)]
      (mapv #(zipmap ks %) rows))))

(defn- qid [uri] (last (str/split uri #"/")))

(defn- point [s]
  (when-let [[_ lon lat] (re-find #"Point\(([-0-9.eE]+) ([-0-9.eE]+)\)" (or s ""))]
    [(parse-double lat) (parse-double lon)]))

(defn load-wikidata [dir]
  (let [munis (->> (read-csv (str dir "/munis.csv"))
                   (keep (fn [{:keys [muni muniLabel coord]}]
                           (when-let [[lat lon] (point coord)]
                             {:qid (qid muni) :name muniLabel :lat lat :lon lon
                              :island (island-of lat lon)})))
                   (reduce (fn [m x] (assoc m (:qid x) x)) {})
                   vals)
        muni-by-qid (into {} (map (juxt :qid identity)) munis)
        locs (->> (read-csv (str dir "/places.csv"))
                  (keep (fn [{:keys [item itemLabel coord muni]}]
                          (let [m (muni-by-qid (qid muni))
                                [lat lon] (point coord)]
                            (when (and m lat (not (re-matches #"Q\d+" itemLabel)))
                              {:qid (qid item) :name itemLabel :lat lat :lon lon
                               :muni (:qid m)}))))
                  (reduce (fn [m x] (assoc m (:qid x) x)) {})
                  vals)]
    {:munis munis :locs locs}))

;; ---------------------------------------------------------------------------
;; Index & resolution

(def muni-aliases
  "Workbook spelling → Wikidata label (folded), where they differ."
  {"santa maria de guia" "santa maria de guia de gran canaria"
   "valsequillo" "valsequillo de gran canaria"
   "san mateo" "vega de san mateo"
   "la villa de mazo" "villa de mazo"})

(defn index [{:keys [munis locs]}]
  {:muni-by-name (into {} (map (juxt (comp fold :name) identity)) munis)
   :munis-by-qid (into {} (map (juxt :qid identity)) munis)
   :locs-by-muni-name (group-by (juxt :muni (comp fold :name)) locs)
   :locs-by-name (group-by (comp fold :name) locs)})

(defn find-muni [idx s]
  (let [f (fold s)]
    (get (:muni-by-name idx) (get muni-aliases f f))))

(defn find-locality
  "Locality by name, inside the given municipality if known, else unique on the island."
  [idx s muni island]
  (let [f (fold s)]
    (or (when muni (first (get (:locs-by-muni-name idx) [(:qid muni) f])))
        (let [cands (cond->> (get (:locs-by-name idx) f)
                      island (filter #(= island (:island ((:munis-by-qid idx) (:muni %))))))]
          (when (= 1 (count cands)) (first cands))))))

(def island-names
  (into {} (concat (map (fn [[id n]] [(fold n) id]) islands)
                   [["gran canaria" "gran-canaria"] ["g canaria" "gran-canaria"]])))

(defn place-id-muni [m] (str "muni-" (:qid m)))
(defn place-id-loc [l] (str "loc-" (:qid l)))

(defn resolve-parts
  "Resolve a list of name parts (most specific first) with an island hint.
  Returns {:place id :island id :level kw} or nil."
  [idx parts island-hint]
  (let [parts (remove str/blank? parts)
        island (or island-hint (some #(island-names (fold %)) parts))
        muni (some #(when-let [m (find-muni idx %)]
                      (when (or (nil? island) (= island (:island m))) m)) parts)
        head (first parts)]
    (cond
      (and head muni (not= (fold head) (fold (:name muni))))
      (if-let [l (find-locality idx head muni island)]
        {:place (place-id-loc l) :island (:island muni) :level :locality}
        {:place (place-id-muni muni) :island (:island muni) :level :municipality})
      muni {:place (place-id-muni muni) :island (:island muni) :level :municipality}
      (and head (not (island-names (fold head))))
      (when-let [l (find-locality idx head nil island)]
        {:place (place-id-loc l) :island (:island ((:munis-by-qid idx) (:muni l))) :level :locality})
      island {:place island :island island :level :island})))
