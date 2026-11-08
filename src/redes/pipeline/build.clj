(ns redes.pipeline.build
  "Workbook → entity maps (persons, places, voyages, sources)."
  (:require [clojure.string :as str]
            [redes.pipeline.gazetteer :as g]))

;; ---------------------------------------------------------------------------
;; Field parsers

(defn- long* [v]
  (cond (int? v) (long v)
        (string? v) (some-> (re-find #"\d+" v) parse-long)))

(defn- year* [v]
  (when-let [y (long* v)] (when (<= 1500 y 1950) y)))

(def civil-map {"Soltero(a)" :single "Casado(a)" :married "Viudo(a)" :widowed
                "Soltero(a) al fallecer" :single})

(defn- signed [s]
  (when s
    (let [f (g/fold s)]
      (cond (re-find #"^(s|5)" f) true
            (re-find #"^(n|k)" f) false))))

(def archive-map {"la palma" :la-palma "lapalma" :la-palma "tenerife" :tenerife
                  "g canaria" :gran-canaria})

(defn- archive [s]
  (when s
    (let [f (g/fold s)]
      (some (fn [[k v]] (when (str/starts-with? f k) v)) archive-map))))

(def kin-stems
  [["padre" "padre"] ["madre" "madre"] ["herman" "hermano/a"] ["tia" "tío/a"] ["tio" "tío/a"]
   ["hij" "hijo/a"] ["espos" "esposo/a"] ["marido" "esposo/a"] ["prim" "primo/a"]
   ["abuel" "abuelo/a"] ["cunad" "cuñado/a"] ["sobrin" "sobrino/a"] ["suegr" "suegro/a"]
   ["niet" "nieto/a"] ["yerno" "yerno"] ["famil" "familia"] ["parient" "pariente"]])

(defn reclamo
  "Kin who sent for / is joined by the emigrant, from 'Otras características'."
  [remarks]
  (when remarks
    (let [f (g/fold remarks)]
      (when-let [[_ w] (re-find #"(?:reclam\w*|unir\w*)\s+(?:a\s+)?(?:su\s+|sus\s+)?(\w+)" f)]
        (or (some (fn [[stem label]] (when (str/starts-with? w stem) label)) kin-stems)
            ;; truncated print ("RECLAMO PAD"): unique stem the fragment is a prefix of
            (let [ls (distinct (keep (fn [[stem label]] (when (and (>= (count w) 3) (str/starts-with? stem w)) label)) kin-stems))]
              (when (= 1 (count ls)) (first ls)))
            "otro")))))

(defn reclamo? [remarks]
  (boolean (some->> remarks g/fold (re-find #"reclam|unir"))))

(defn stamp [remarks]
  (some->> remarks (re-find #"(?i)CU[ÑN]O\s+(?:DE\s+)?(.+?)\s*(?:\.\s*(?:RECLAM|UNIR|CONSENT|ESP|HER).*|\.?\s*$)") second str/trim))

(defn stamp-muni
  "Municipality of a 'Cuño de X' stamp (STA. CRUZ resolved by the archive island)."
  [idx stamp-text archive]
  (when stamp-text
    (let [f (g/fold stamp-text)
          nm (cond (re-find #"^s(an)?ta?\s*cruz" f) (case archive :la-palma "Santa Cruz de La Palma"
                                                       :tenerife "Santa Cruz de Tenerife" nil)
                   (= f "los llanos") "Los Llanos de Aridane"
                   (= f "san andres y sauces") "San Andrés y Sauces"
                   :else stamp-text)]
      (some->> nm (g/find-muni idx)))))

(defn stamp-island [idx stamp-text archive]
  (:island (stamp-muni idx stamp-text archive)))

(defn- title-case [s]
  (when s
    (->> (str/split (str/lower-case s) #"\s+")
         (map-indexed (fn [i w] (if (and (pos? i) (#{"de" "del" "la" "las" "los" "y" "el"} w))
                                   w (str/capitalize w))))
         (str/join " "))))

(defn ship-name [s]
  (when-let [s (some-> s (str/replace #"[|\]\[]" "") str/trim not-empty)]
    (title-case s)))

(def citation-re
  ;; the continuation page is missing for printed page 559: "páginas PDF 562" (one page)
  ;; record numbers can be OCR noise ("GRA!", "—"), so accept any text there
  #"registro n\.º ([^,]+), páginas PDF (\d+)(?:[–-](\d+))?, página impresa (\d+), fila (\d+)")

(defn- source-of [citation]
  (let [c (or citation "")]
    (cond
      (str/starts-with? c "Archivo Histórico de Teguise") "teguise"
      (re-find #"^(Francisco de )?Santa Cruz" c) "santa-cruz-mallen"
      (re-find #"Nieto" c) "nieto-cortadellas"
      (re-find #"^Navarrete" c) "navarrete-holguin"
      (str/starts-with? c "SCLH") "catedral-habana"
      (str/starts-with? c "IPSJNR") "parroquia-remedios"
      (str/blank? c) "sin-fuente"
      :else "genealogias-varias")))

(def ref-teguise "Hernández Delgado, Francisco. 2002. Relación de emigrantes canarios: Clasificados por orden alfabético. Vol. I-III, Archivo Histórico Municipal de Teguise, Lanzarote, Spain.")
(def ref-barajas "Libro de Barajas: Matrimonios, 1584–1622. Archivo de la Sagrada Catedral de La Habana, Cuba. Transcribed by Jorge E. Delgadillo Núñez. Slave Societies Digital Archive, Vanderbilt University, 2016.")
(def ref-remedios "Libros de Matrimonios de Remedios. Transcribed by José Antonio Mahy, Cuban Genealogy Club of Miami, 2009.")
(def ref-navarrete "Navarrete, William, and Manuel F. Díaz Álvarez. 2023. Primeras Familias, Poblado y Ciudad San Isidoro de Holguín: Estudio de Genealogía Cubana. Primera edición. [Miami, Florida]: UnosOtrosEdiciones.")
(def ref-santa-cruz "Santa Cruz y Mallén, Francisco Xavier de. 1940-1988, Historia de Familias Cubanas, 1-9 Vols., La Habana: “Editorial Hércules”.")

(def sources
  ;; references as given by the project team (Fuentes.odt)
  [{:source/id "teguise" :source/kind :list
    :source/title "Relación de emigrantes canarios" :source/citation ref-teguise}
   {:source/id "santa-cruz-mallen" :source/kind :genealogy
    :source/title "Historia de Familias Cubanas" :source/citation ref-santa-cruz}
   {:source/id "nieto-cortadellas" :source/kind :genealogy
    :source/title "Genealogías habaneras"
    :source/citation "Nieto y Cortadellas, Rafael. Genealogías habaneras."}
   {:source/id "navarrete-holguin" :source/kind :genealogy
    :source/title "Primeras Familias, Poblado y Ciudad San Isidoro de Holguín" :source/citation ref-navarrete}
   {:source/id "catedral-habana" :source/kind :parish
    :source/title "Libro de Barajas: Matrimonios, 1584–1622" :source/citation ref-barajas}
   {:source/id "parroquia-remedios" :source/kind :parish
    :source/title "Libros de Matrimonios de Remedios" :source/citation ref-remedios}
   {:source/id "genealogias-varias" :source/kind :genealogy
    :source/title "Otras genealogías y comunicaciones"
    :source/citation "Diversas genealogías publicadas, foros y comunicaciones privadas citadas en cada registro."}
   {:source/id "sin-fuente" :source/kind :other
    :source/title "Sin fuente indicada" :source/citation ""}])

;; ---------------------------------------------------------------------------
;; Places

(defn split-place [s] (when s (mapv str/trim (str/split s #",\s*"))))

(defn birth-place
  "Resolve birth place from Personas fields."
  [idx {:keys [birth-place birth-muni birth-island]}]
  (let [isl (some-> birth-island g/fold g/island-names)
        parts (cond-> []
                (and birth-place (not= birth-place birth-muni)) (into (split-place birth-place))
                birth-muni (conj birth-muni))]
    (or (g/resolve-parts idx parts isl)
        (when isl {:place isl :island isl :level :island}))))

(defn residence-place [idx residence]
  (let [parts (split-place residence)]
    (when (some #(= "España" %) parts)
      (g/resolve-parts idx (remove #{"España" "Las Palmas" "Santa Cruz de Tenerife"} parts) nil))))

;; ---------------------------------------------------------------------------
;; Persons

(defn- volume
  "Volume of the printed compilation from the scan page (covers at PDF pages 1, 254, 561)."
  [pdf-page]
  (cond (< pdf-page 254) "I" (< pdf-page 561) "II" :else "III"))

(defn public-citation
  "Readable citation of the printed source (never the working scan)."
  [c]
  (or
   (when-let [[_ no pg _ printed row] (re-find citation-re c)]
     (str "Hernández Delgado, Francisco. 2002. Relación de emigrantes canarios, vol. " (volume (parse-long pg))
          ", p. " printed ", fila " row " (registro n.º " (str/trim no) "). Archivo Histórico Municipal de Teguise."
          (some->> (re-find #"Corregido:.*$" c) (str " "))))
   (when-let [[_ folio y m d] (re-find #"^SCLH_MAT_BARAJAS_F([0-9]+[rv]?)_(\d{4})(?:(\d{2})(\d{2}))?" c)]
     (str "Libro de Barajas: Matrimonios, 1584–1622, f. " folio ", " (if d (str d "-" m "-" y) y)
          ". Archivo de la Sagrada Catedral de La Habana. Transcr. Jorge E. Delgadillo Núñez, Slave Societies Digital Archive, Vanderbilt University, 2016."))
   (when-let [[_ libro ref] (re-find #"^IPSJNR_MAT_L(\w+)_(\w+)" c)]
     (str "Libros de Matrimonios de Remedios, libro " libro ", ref. " ref
          ". Transcr. José Antonio Mahy, Cuban Genealogy Club of Miami, 2009."))
   ;; drop page references to the team's working PDF copies of printed books
   (-> c (str/replace #"\s*PDF:\s*[0-9 ,.\-–]+\.?" "") str/trim)))

(defn- display-name [{:keys [given surname1 surname2]}]
  (str/join " " (remove str/blank? [given surname1 surname2])))

(defn- events [{:keys [birth-date baptism-date baptism-place marriage-date marriage-place
                       death-date death-place]}]
  (->> [[:birth birth-date nil] [:baptism baptism-date baptism-place]
        [:marriage marriage-date marriage-place] [:death death-date death-place]]
       (keep (fn [[t d p]]
               (when (or d p)
                 (cond-> {:event/type t}
                   d (assoc :event/date (str d))
                   (year* (re-find #"\d{4}" (str d))) (assoc :event/year (year* (re-find #"\d{4}" (str d))))
                   p (assoc :event/place-text p)))))
       vec))

(defn person
  [idx raw-by-pos p]
  (let [src (source-of (:citation p))
        [_ _ pg1 pg2 printed row] (when (= src "teguise") (re-find citation-re (:citation p)))
        pos (when pg1 [(parse-long pg1) (parse-long row)])
        raw (when pos (raw-by-pos pos))
        id (if pos (str "T-" (first pos) "-" (second pos)) (str "G-" (:no p)))
        bp (birth-place idx p)
        rp (residence-place idx (:residence p))
        dest (g/destinations (:destination p))
        arch (archive (:archive raw))
        age (let [a (long* (:age raw))] (when (and a (< 0 a 111)) a))
        year (or (year* (:year raw)) (year* (:residence-year p))
                 (some :event/year (events p)))
        ship (ship-name (:ship raw))
        remarks (:remarks raw)
        nm (display-name p)]
    (cond-> {:person/id id
             :person/name (if (str/blank? nm) (or (:name raw) "(sin nombre)") nm)
             :person/source [:source/id src]
             :person/search (g/fold (str/join " " [nm (:name raw) (:other-names p) (:other-surnames p)]))}
      (:no p) (assoc :person/no (str (:no p)))
      (:name raw) (assoc :person/name-raw (:name raw))
      (:given p) (assoc :person/given (:given p))
      (:surname1 p) (assoc :person/surname1 (:surname1 p))
      (:surname2 p) (assoc :person/surname2 (:surname2 p))
      (:surname-group p) (assoc :person/surname-group (:surname-group p))
      (#{"M" "F"} (:sex p)) (assoc :person/sex (keyword (str/lower-case (:sex p))))
      (civil-map (:civil p)) (assoc :person/civil (civil-map (:civil p)))
      age (assoc :person/age age)
      (year* (:birth-year p)) (assoc :person/birth-year (year* (:birth-year p)))
      (:birth-date p) (assoc :person/birth-date (str (:birth-date p)))
      (some? (signed (:signature raw))) (assoc :person/signed (signed (:signature raw)))
      (:occupation p) (assoc :person/occupation (:occupation p))
      (:religion p) (assoc :person/religion (:religion p))
      (:spouse p) (assoc :person/spouse (:spouse p))
      (:father p) (assoc :person/father (:father p))
      (:mother p) (assoc :person/mother (:mother p))
      (:siblings p) (assoc :person/siblings (:siblings p))
      (:children p) (assoc :person/children (:children p))
      (:grandparents p) (assoc :person/grandparents (:grandparents p))
      (:race-marker p) (assoc :person/race-marker (:race-marker p))
      (:title p) (assoc :person/title (:title p))
      bp (assoc :person/origin [:place/id (:place bp)])
      ;; home: most specific known place (birth, else Canarian residence, else stamp municipality)
      (or (#{:locality :municipality} (:level bp)) (#{:locality :municipality} (:level rp))
          (stamp-muni idx (stamp remarks) arch))
      (assoc :person/home [:place/id (cond (#{:locality :municipality} (:level bp)) (:place bp)
                                           (#{:locality :municipality} (:level rp)) (:place rp)
                                           :else (g/place-id-muni (stamp-muni idx (stamp remarks) arch)))])
      (:island bp) (assoc :person/island [:place/id (:island bp)])
      (and (not (:island bp)) (:island rp)) (assoc :person/island [:place/id (:island rp)])
      ;; fallback: island of the municipality whose stamp (cuño) the record carries
      (and (not (:island bp)) (not (:island rp)) (stamp-island idx (stamp remarks) arch))
      (assoc :person/island [:place/id (stamp-island idx (stamp remarks) arch)])
      (or (:birth-place p) (:birth-muni p) (:natural raw))
      (assoc :person/origin-text (or (:natural raw) (:birth-place p) (:birth-muni p)))
      rp (assoc :person/residence [:place/id (:place rp)])
      (or (:vecino raw) (:residence p)) (assoc :person/residence-text (or (:vecino raw) (:residence p)))
      dest (assoc :person/destination [:place/id (first dest)])
      (:destination p) (assoc :person/destination-text (:destination p))
      year (assoc :person/year year)
      (and ship year arch) (assoc :person/voyage [:voyage/id (str ship "|" year "|" (name arch))])
      (long* (:family raw)) (assoc :person/group-size (long* (:family raw)))
      (reclamo? remarks) (assoc :person/reclamo (or (reclamo remarks) "otro"))
      (stamp remarks) (assoc :person/stamp (stamp remarks))
      remarks (assoc :person/remarks remarks)
      arch (assoc :person/archive arch)
      (:notes p) (assoc :person/notes (:notes p))
      (:citation p) (assoc :person/citation (public-citation (:citation p)))
      (:literal p) (assoc :person/literal (:literal p))
      (:doubtful raw) (assoc :person/doubtful (vec (str/split (:doubtful raw) #",\s*")))
      pos (assoc :person/pdf-page (parse-long pg1) :person/pdf-page2 (some-> pg2 parse-long)
                 :person/printed-page (parse-long printed) :person/row (parse-long row))
      (and pos (nil? pg2)) (dissoc :person/pdf-page2)
      (seq (events p)) (assoc :person/events (events p)))))

;; ---------------------------------------------------------------------------
;; Assemble

(defn- place-entities [wd]
  (concat
   (for [[id n lat lon] g/islands]
     {:place/id id :place/name n :place/kind :island :place/lat lat :place/lon lon})
   (for [[_ [id n kind lat lon]] g/destinations]
     (cond-> {:place/id id :place/name n :place/kind kind}
       lat (assoc :place/lat lat :place/lon lon)))
   (for [[id n lat lon parent] g/ports]
     {:place/id id :place/name n :place/kind :port :place/lat lat :place/lon lon
      :place/parent [:place/id parent]})
   (for [m (:munis wd)]
     (cond-> {:place/id (g/place-id-muni m) :place/name (:name m) :place/kind :municipality
              :place/lat (:lat m) :place/lon (:lon m) :place/wikidata (:qid m)}
       (:island m) (assoc :place/parent [:place/id (:island m)])))
   (for [l (:locs wd)]
     {:place/id (g/place-id-loc l) :place/name (:name l) :place/kind :locality
      :place/lat (:lat l) :place/lon (:lon l) :place/wikidata (:qid l)
      :place/parent [:place/id (str "muni-" (:muni l))]})))

(def left-keys [:no :name :natural :vecino :island :civil :age :signature :destination :year])

(defn- citation [no pg pg2 printed row note]
  (str "Archivo Histórico de Teguise, canarios.pdf, registro n.º " no ", páginas PDF " pg "–" pg2
       ", página impresa " printed ", fila " row "." (when note (str " " note))))

(defn apply-corrections
  "Apply data/raw/corrections.edn to the two sheets (see that file)."
  [{:keys [personas originales]} {:keys [left-shift missing fixes]}]
  (let [by-pos (into {} (map (juxt (juxt :pdf-page :row) identity)) originales)
        note "Corregido: desplazamiento de fila en la transcripción, cotejado con el impreso."
        fix-raw (fn [orig]
                  (let [m (into {} (map (juxt (juxt :pdf-page :row) identity)) orig)
                        m (reduce (fn [m {:keys [page rows numbers right]}]
                                    (let [[a b] rows]
                                      (reduce (fn [m k]          ; printed row k ← left half of sheet row k+1
                                                (assoc m [page k]
                                                       (-> (apply dissoc (or (by-pos [page k]) (get right k)) left-keys)
                                                           (merge (select-keys (by-pos [page (inc k)]) left-keys))
                                                           (assoc :no (numbers k) :doubtful nil :corrected true))))
                                              m (range (dec a) b))))
                                  m left-shift)
                        m (reduce (fn [m {:keys [page row raw]}]
                                    (assoc m [page row] (-> (apply dissoc (by-pos [page row]) left-keys)
                                                            (merge raw) (assoc :doubtful nil :corrected true))))
                                  m missing)]
                    (vec (vals m))))
        shifted (into {} (for [{:keys [page rows numbers]} left-shift
                               r (range (first rows) (inc (second rows)))]
                           [[page r] {:row (dec r) :no (numbers (dec r))}]))
        unlocated (into {} (for [{:keys [page page2 printed row persona]} missing]
                             [(:no persona) (citation (:no persona) page page2 printed row note)]))
        fix-shifted (fn [p]
                      (if-let [[_ _ pg1 pg2 printed row] (some->> (:citation p) (re-find citation-re))]
                        (if-let [{new-row :row no :no} (shifted [(parse-long pg1) (parse-long row)])]
                          (-> p (dissoc :notes)
                              (assoc :no no :citation (citation no pg1 pg2 printed new-row note)))
                          p)
                        p))
        fix-persona (fn [p]
                      (if (and (unlocated (str (:no p))) (re-find #"páginas PDF \?" (str (:citation p))))
                        (assoc p :citation (unlocated (str (:no p))))
                        (fix-shifted p)))]
    (let [fix-at (into {} (map (juxt (juxt :page :row) identity)) fixes)
          pos-of (fn [p] (when-let [[_ _ pg _ _ row] (some->> (:citation p) (re-find citation-re))]
                           [(parse-long pg) (parse-long row)]))]
      {:originales (mapv (fn [r] (if-let [f (fix-at [(:pdf-page r) (:row r)])] (merge r (:raw f)) r))
                         (fix-raw originales))
       :personas (mapv (fn [p] (if-let [f (fix-at (pos-of p))] (merge p (:persona f)) p))
                       (into (mapv fix-persona personas)
                             ;; a missing row may already be in 'Personas' without a location
                             ;; ("páginas PDF ?"): locate that record instead of adding a duplicate
                             (for [{:keys [page page2 printed row persona]} missing
                                   :when (not-any? #(and (= (str (:no %)) (:no persona))
                                                         (re-find #"páginas PDF \?" (str (:citation %))))
                                                   personas)]
                               (assoc persona :citation (citation (:no persona) page page2 printed row note)))))})))

(defn build [{:keys [personas originales]} wd]
  (let [idx (g/index wd)
        raw-by-pos (into {} (map (juxt (juxt :pdf-page :row) identity)) originales)
        persons (->> (mapv #(person idx raw-by-pos %) personas)
                     ;; a few genealogical numbers repeat: suffix them
                     (reduce (fn [[seen acc] p]
                               (let [n (get seen (:person/id p) 0)]
                                 [(update seen (:person/id p) (fnil inc 0))
                                  (conj acc (cond-> p (pos? n) (update :person/id str "-" (inc n))))]))
                             [{} []])
                     second)
        voyages (->> persons
                     (keep :person/voyage)
                     distinct
                     (map (fn [[_ vid]]
                            (let [[ship year arch] (str/split vid #"\|")
                                  dest-port (->> persons
                                                 (filter #(= vid (second (:person/voyage %))))
                                                 (keep :person/destination-text)
                                                 frequencies (sort-by val >) ffirst
                                                 g/destinations last)]
                              (cond-> {:voyage/id vid :voyage/ship ship
                                       :voyage/year (parse-long year)
                                       :voyage/archive (keyword arch)
                                       :voyage/from [:place/id (g/archive->port (keyword arch))]}
                                dest-port (assoc :voyage/to [:place/id dest-port]))))))
        used (into #{} (comp (mapcat (juxt :person/origin :person/residence :person/island :person/destination :person/home))
                             (keep second)) persons)
        all-places (place-entities wd)
        by-id (into {} (map (juxt :place/id identity)) all-places)
        keep-ids (loop [ids (into used (map :place/id) (remove #(#{:locality :municipality} (:place/kind %)) all-places))]
                   (let [more (into ids (keep #(some-> (by-id %) :place/parent second)) ids)]
                     (if (= more ids) ids (recur more))))]
    {:sources sources
     :places (filterv #(keep-ids (:place/id %)) all-places)
     :voyages (vec voyages)
     :persons persons}))
