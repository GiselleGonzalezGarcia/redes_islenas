(ns redes.client.views.lugares
  "Towns (pueblos) and ships (barcos): index pages and detail pages."
  (:require ["@observablehq/plot" :as Plot]
            [org.replikativ.spindel.core :as s :refer-macros [spin]]
            [org.replikativ.spindel.effects.track :refer [track]]
            [org.replikativ.spindel.incremental.interval :as iv]
            [org.replikativ.spindel.dom.elements :as el
             :refer-macros [div h1 h2 p ul li span table thead tbody tr th td section article b]]
            [org.replikativ.spindel.dom.foreign :refer-macros [foreign-node]]
            [datahike.api :as d]
            [clojure.string :as str]
            [redes.client.state :as state]
            [redes.client.lookup :as lk]
            [redes.client.i18n :refer [t]]
            [redes.client.views.common :as c]))

(def L {:pueblos {:es "Pueblos" :en "Towns"}
        :barcos {:es "Barcos" :en "Ships"}
        :personas {:es "Personas" :en "People"}
        :pueblos-intro {:es "Municipios de origen o de vecindad de los emigrantes, agrupados por isla. Entre paréntesis, nacidos en el municipio / vecinos."
                        :en "Municipalities where the emigrants were born or lived, by island. In brackets: born there / residents."}
        :barcos-intro {:es "Barcos que nombra la fuente. Solo uno de cada cuatro registros indica el barco; la misma nave puede figurar con grafías distintas."
                       :en "Ships named in the source. Only one record in four names the ship; the same vessel may appear under different spellings."}
        :nacidos {:es "nacidos aquí" :en "born here"}
        :vecinos {:es "vecinos" :en "residents"}
        :salidas {:es "Salidas por año" :en "Departures per year"}
        :destinos {:es "Destinos" :en "Destinations"}
        :apellidos {:es "Apellidos más frecuentes" :en "Most frequent surnames"}
        :barcos-de {:es "Barcos" :en "Ships"}
        :lista {:es "Emigrantes" :en "Emigrants"}
        :viajes {:es "Viajes registrados" :en "Recorded voyages"}
        :pasajeros {:es "pasajeros registrados" :en "registered passengers"}
        :otras {:es "Otras islas" :en "Other islands"}
        :no-pueblo {:es "No hay ningún pueblo con ese identificador." :en "There is no town with that identifier."}
        :no-barco {:es "No hay ningún barco con ese identificador." :en "There is no ship with that identifier."}})

(defn- l [lang k] (get-in L [k lang]))

(defn sub-nav [lang current]
  (div {:class "subnav"}
    (c/link {:key "p" :aria-current (when (= current :explorar) "page")} :explorar {} (l lang :personas))
    (c/link {:key "u" :aria-current (when (= current :pueblos) "page")} :pueblos {} (l lang :pueblos))
    (c/link {:key "b" :aria-current (when (= current :barcos) "page")} :barcos {} (l lang :barcos))))

(def island-order ["tenerife" "gran-canaria" "la-palma" "la-gomera" "el-hierro" "lanzarote" "fuerteventura"])

(defn pueblos-spin []
  (spin
    (let [lang (iv/get-new (track state/lang-signal))
          db (iv/get-new (track state/db-signal))]
      (div {:class "pagina"}
        (sub-nav lang :pueblos)
        (h1 (l lang :pueblos))
        (p {:class "intro"} (l lang :pueblos-intro))
        (if-not db
          (p (t lang :cargando))
          (let [{:keys [munis]} (lk/lookup db)
                by-island (group-by :island munis)]
            (div {:class "islas"}
              (for [isl island-order :let [ms (by-island isl)] :when (seq ms)]
                (section {:class "isla" :key isl}
                  (h2 (:island-name (first ms)))
                  (ul {:class "pueblos"}
                    (for [m (sort-by :name ms)]
                      (li {:key (:id m)}
                        (c/link {} :pueblo {:id (:id m)} (:name m))
                        (span {:class "n"} (str " (" (:born m) " / " (:lives m) ")"))))))))))))))

(defn- year-chart [k rows]
  (foreign-node
   {:key k :class "grafico mini"
    :on-mount (fn [el]
                (.replaceChildren
                 el (Plot/plot (clj->js {:width (max 300 (.-clientWidth el)) :height 160 :marginLeft 36
                                         :style {:fontFamily "Source Sans 3, system-ui, sans-serif" :fontSize "12px" :background "transparent"}
                                         :x {:label nil :tickFormat "d" :domain [1840 1901]}
                                         :y {:label nil :grid true}
                                         :marks [(.rectY Plot (clj->js rows) #js {:x "year" :interval 1 :y "n" :fill "#1767A6" :inset 0.5 :tip true})
                                                 (.ruleY Plot #js [0] #js {:stroke "#1B2733"})]}))))}))

(defn- freq-line [pairs]
  (str/join " · " (map (fn [[k n]] (str k " " n)) pairs)))

(defn- person-rows [db eids]
  (->> (d/pull-many db [:person/id :person/name :person/surname1 :person/year :person/age
                        {:person/destination [:place/name]} {:person/voyage [:voyage/ship]}] eids)
       (sort-by (juxt #(or (:person/year %) 9999) :person/name))))

(defn- person-table [lang rows & [{:keys [ship?] :or {ship? true}}]]
  (table {:class "registro"}
    (thead (tr (th (t lang :col/nombre)) (th {:class "num"} (t lang :col/anio)) (th {:class "num opcional"} (t lang :col/edad))
               (th (t lang :col/destino)) (when ship? (th {:class "opcional"} (t lang :f/barco)))))
    (tbody
      (for [r rows]
        (tr {:key (:person/id r) :on-click (fn [_] (state/navigate! :persona {:id (:person/id r)}))}
          (td {:class "nombre"} (c/link {} :persona {:id (:person/id r)} (:person/name r)))
          (td {:class "num"} (:person/year r))
          (td {:class "num opcional"} (:person/age r))
          (td (get-in r [:person/destination :place/name]))
          (when ship? (td {:class "opcional"} (get-in r [:person/voyage :voyage/ship]))))))))

(defn pueblo-spin [id]
  (spin
    (let [lang (iv/get-new (track state/lang-signal))
          db (iv/get-new (track state/db-signal))]
      (if-not db
        (div {:class "pagina"} (p (t lang :cargando)))
        (let [{:keys [muni-by-id born lives munis]} (lk/lookup db)
              m (muni-by-id id)
              info (some #(when (= id (:id %)) %) munis)]
          (if-not m
            (div {:class "pagina"} (p (l lang :no-pueblo)))
            (let [rows (person-rows db (distinct (concat (born m) (lives m))))
                  per-year (->> rows (keep :person/year) (filter #(<= 1840 % 1900)) frequencies
                                (map (fn [[y n]] {:year y :n n})))
                  dests (->> rows (keep #(get-in % [:person/destination :place/name])) frequencies (sort-by val >))
                  surnames (->> rows (keep :person/surname1) frequencies (sort-by val >) (take 12))
                  ships (->> rows (keep #(get-in % [:person/voyage :voyage/ship])) frequencies (sort-by val >) (take 10))]
              (article {:class "pagina"}
                (sub-nav lang :pueblos)
                (h1 (:name info))
                (p {:class "sub"} (str (:island-name info) " · " (:born info) " " (l lang :nacidos) " · "
                                       (:lives info) " " (l lang :vecinos)))
                (div {:class "resumen"}
                  (section {:key "y"} (h2 (l lang :salidas)) (year-chart (str "y-" id) per-year))
                  (section {:key "d"} (h2 (l lang :destinos)) (p (freq-line dests))
                    (h2 (l lang :apellidos)) (p (freq-line surnames))
                    (when (seq ships)
                      (div (h2 (l lang :barcos-de))
                           (p (map-indexed (fn [i [sh n]]
                                             (span {:key sh} (when (pos? i) " · ")
                                                   (c/link {} :barco {:id ((:slug (lk/lookup db)) sh)} sh) (str " " n)))
                                           ships))))))
                (h2 (str (l lang :lista) " (" (count rows) ")"))
                (person-table lang rows)))))))))

(defn barcos-spin []
  (spin
    (let [lang (iv/get-new (track state/lang-signal))
          db (iv/get-new (track state/db-signal))]
      (div {:class "pagina"}
        (sub-nav lang :barcos)
        (h1 (l lang :barcos))
        (p {:class "intro"} (l lang :barcos-intro))
        (if-not db
          (p (t lang :cargando))
          (let [{:keys [ships]} (lk/lookup db)]
            (ul {:class "pueblos barcos"}
              (for [sh ships]
                (li {:key (:id sh)}
                  (c/link {} :barco {:id (:id sh)} (:name sh))
                  (span {:class "n"} (str " (" (:n sh) ")")))))))))))

(defn barco-spin [id]
  (spin
    (let [lang (iv/get-new (track state/lang-signal))
          db (iv/get-new (track state/db-signal))]
      (if-not db
        (div {:class "pagina"} (p (t lang :cargando)))
        (let [{:keys [ship-by-id persons-by-voyage]} (lk/lookup db)
              vs (ship-by-id id)]
          (if-not (seq vs)
            (div {:class "pagina"} (p (l lang :no-barco)))
            (let [voyages (->> (d/pull-many db [:db/id :voyage/ship :voyage/year :voyage/archive
                                                {:voyage/from [:place/name]} {:voyage/to [:place/name]}] vs)
                               (sort-by :voyage/year))
                  total (reduce + (map #(count (persons-by-voyage (:db/id %))) voyages))]
              (article {:class "pagina"}
                (sub-nav lang :barcos)
                (h1 (:voyage/ship (first voyages)))
                (p {:class "sub"} (str (count voyages) " " (str/lower-case (l lang :viajes)) " · " total " " (l lang :pasajeros)))
                (for [v voyages
                      :let [rows (person-rows db (persons-by-voyage (:db/id v)))]]
                  (section {:key (str (:db/id v)) :class "viaje"}
                    (h2 (str (:voyage/year v) ": " (get-in v [:voyage/from :place/name]) " → "
                             (or (get-in v [:voyage/to :place/name]) "?") " (" (count rows) ")"))
                    (person-table lang rows {:ship? false})))))))))))
