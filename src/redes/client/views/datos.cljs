(ns redes.client.views.datos
  "Data-science views over the register (Observable Plot), each with a short
  reading and a table view."
  (:require ["@observablehq/plot" :as Plot]
            [org.replikativ.spindel.core :as s :refer-macros [spin]]
            [org.replikativ.spindel.effects.track :refer [track]]
            [org.replikativ.spindel.incremental.interval :as iv]
            [org.replikativ.spindel.dom.elements :as el
             :refer-macros [div h1 h2 p details summary table thead tbody tr th td section]]
            [org.replikativ.spindel.dom.foreign :refer-macros [foreign-node]]
            [cognitect.transit :as t]
            [redes.client.state :as state]
            [redes.client.views.common :as c]))

;; ---------------------------------------------------------------------------
;; Palette (validated against the page surface #EEF1EF, adjacent pairs)

(def ink "#1B2733")
(def muted "#4A5866")
(def none-gray "#9AA3A8")
(def serie ["#1767A6" "#B0184F" "#A87300" "#14906A"])

(def islands ["tenerife" "gran-canaria" "la-palma" "otras" "sin-dato"])
(def island-label {:es {"tenerife" "Tenerife" "gran-canaria" "Gran Canaria" "la-palma" "La Palma"
                        "otras" "Otras islas" "sin-dato" "Sin dato"}
                   :en {"tenerife" "Tenerife" "gran-canaria" "Gran Canaria" "la-palma" "La Palma"
                        "otras" "Other islands" "sin-dato" "Unknown"}})
(def island-color (zipmap islands (conj serie none-gray)))

(def dests ["cuba" "venezuela" "puerto-rico" "uruguay" "otros"])
(def dest-label {:es {"cuba" "Cuba" "venezuela" "Venezuela" "puerto-rico" "Puerto Rico" "uruguay" "Uruguay" "otros" "Otros"}
                 :en {"cuba" "Cuba" "venezuela" "Venezuela" "puerto-rico" "Puerto Rico" "uruguay" "Uruguay" "otros" "Other"}})

(def events
  [{:year 1852 :es "Puertos Francos" :en "Free Ports law"}
   {:year 1868 :es "Guerra de los Diez Años" :en "Ten Years' War"}
   {:year 1878 :es "Paz de Zanjón" :en "Peace of Zanjón"}
   {:year 1895 :es "Guerra de Independencia" :en "Cuban War of Independence"}])

;; ---------------------------------------------------------------------------
;; Texts

(def txt
  {:titulo {:es "Lo que cuentan los datos" :en "What the data show"}
   :intro {:es "Gráficos sobre los 15.298 registros de la Relación de emigrantes canarios. Cada gráfico tiene su tabla de datos. Recuerda que la compilación solo llega hasta los apellidos que empiezan por S y que cada isla aparece en los años en que sus registros entraron en ella."
           :en "Charts over the 15,298 records of the list of Canarian emigrants. Each chart has its data table. Keep in mind that the compilation only reaches surnames beginning with S, and that each island appears in the years in which its records made it into the list."}
   :tabla {:es "Ver los datos" :en "Show the data"}
   :anio-q {:es "¿Cuándo salieron, y desde qué isla?" :en "When did they leave, and from which island?"}
   :anio-t {:es "Dos oleadas dominan el registro: los años cincuenta, con salidas de Gran Canaria y Tenerife, y los años noventa, sobre todo desde La Palma y, en menor medida, La Gomera. La isla es la de nacimiento; si falta, la de vecindad o la del municipio que selló el documento."
            :en "Two waves dominate the register: the 1850s, with departures from Gran Canaria and Tenerife, and the 1890s, mostly from La Palma and, to a lesser extent, La Gomera. The island is the island of birth; failing that, of residence or of the municipality that stamped the document."}
   :piramide-q {:es "¿Quiénes eran?" :en "Who were they?"}
   :piramide-t {:es "Edad declarada y sexo. Emigraban sobre todo hombres jóvenes; el grupo más numeroso es el de los muchachos de 15 a 19 años."
                :en "Stated age and sex. Mostly young men emigrated; the largest group is boys aged 15 to 19."}
   :hombres {:es "Hombres" :en "Men"}
   :mujeres {:es "Mujeres" :en "Women"}
   :firma-q {:es "¿Sabían firmar?" :en "Could they sign their name?"}
   :firma-t {:es "Porcentaje de registros con firma, por década y sexo. La firma es una medida clásica, aunque imperfecta, de alfabetización."
             :en "Share of records with a signature, by decade and sex. Signing is a classic, if imperfect, measure of literacy."}
   :destino-q {:es "¿Adónde iban?" :en "Where were they going?"}
   :destino-t {:es "Destino declarado, por década. Cuba es el destino de más de nueve de cada diez emigrantes; Venezuela tiene su momento en los años setenta."
               :en "Stated destination, by decade. Cuba is the destination of more than nine in ten emigrants; Venezuela has its moment in the 1870s."}
   :reclamo-q {:es "¿Quién los llamaba?" :en "Who sent for them?"}
   :reclamo-t {:es "Más de mil registros anotan que el emigrante iba reclamado por un familiar ya instalado en América, o a reunirse con él. Son las redes que dan nombre a este proyecto. «Otro» agrupa anotaciones sin parentesco legible."
               :en "More than a thousand records note that the emigrant was sent for by, or going to join, a relative already settled in the Americas. These are the networks the project is named after. ‘otro’ groups notes without a legible kinship term."}
   :origen-q {:es "¿De qué pueblos?" :en "From which towns?"}
   :origen-t {:es "Los 25 municipios con más emigrantes localizados, con el color de su isla."
              :en "The 25 municipalities with the most located emigrants, coloured by island."}
   :grupo-q {:es "¿Solos o en familia?" :en "Alone or with family?"}
   :grupo-t {:es "Personas que figuran en el mismo registro (columna «otros datos familiares»). La gran mayoría viajaba sola."
             :en "People listed in the same record (the ‘other family data’ column). The great majority travelled alone."}
   :barcos-q {:es "¿En qué barcos?" :en "On which ships?"}
   :barcos-t {:es "Los quince barcos con más pasajeros registrados. Solo uno de cada cuatro registros indica el barco."
              :en "The fifteen ships with the most registered passengers. Only one record in four names the ship."}
   :letras-q {:es "¿Qué falta en la fuente?" :en "What is missing from the source?"}
   :letras-t {:es "Registros por inicial del primer apellido. Los tres tomos conservados terminan en la S: los apellidos de la T a la Z no están en la compilación."
              :en "Records by initial of the first surname. The three surviving volumes end at S: surnames from T to Z are not in the compilation."}
   :n {:es "personas" :en "people"}
   :pct {:es "% con firma" :en "% signed"}})

(defn tx [lang k] (get-in txt [k lang]))

;; ---------------------------------------------------------------------------
;; Plot helpers

(defn- base [width]
  {:width width :marginLeft 48 :style {:fontFamily "Source Sans 3, system-ui, sans-serif" :fontSize "13px" :color ink :background "transparent"}})

(defn chart
  "A Plot chart that re-renders on width changes. f: width → Plot options (clj)."
  [k f]
  (let [ro (atom nil)]
    (foreign-node
     {:key k :class "grafico"
      :on-mount (fn [el]
                  (let [draw! (fn []
                                (let [w (max 300 (.-clientWidth el))]
                                  (.replaceChildren el (Plot/plot (clj->js (merge (base w) (f w)))))))]
                    (draw!)
                    (reset! ro (doto (js/ResizeObserver. (let [last (atom 0)]
                                                           (fn [_] (let [w (.-clientWidth el)]
                                                                     (when (> (js/Math.abs (- w @last)) 20)
                                                                       (reset! last w) (draw!))))))
                                 (.observe el)))))
      :on-unmount (fn [_] (some-> @ro .disconnect))})))

(defn data-table [lang cols rows]
  (details {:class "tabla-datos"}
    (summary (tx lang :tabla))
    (table {:class "registro"}
      (thead (tr (map-indexed (fn [i [label _]] (th {:key (str i)} label)) cols)))
      (tbody (map-indexed (fn [i r] (tr {:key (str i)} (map-indexed (fn [j [_ f]] (td {:key (str j)} (str (f r)))) cols))) rows)))))

(defn panel [lang k q-key t-key chart-el table-el]
  (section {:class "panel" :key k}
    (h2 (tx lang q-key))
    (p (tx lang t-key))
    chart-el
    table-el))

;; ---------------------------------------------------------------------------
;; Charts

(defn por-anio [lang {:keys [by-year-island]}]
  (let [lab (island-label lang)
        rows (map #(assoc % :label (lab (:island %))) by-year-island)
        ymax (->> by-year-island (group-by :year) vals (map #(reduce + (map :n %))) (apply max))]
    (panel lang "anio" :anio-q :anio-t
           (chart (str "anio-" (name lang))
                  (fn [w]
                    {:height 340 :marginTop 30
                     :x {:label nil :tickFormat "d" :domain [1840 1901]}
                     :y {:label (tx lang :n) :grid true}
                     :color {:domain (mapv lab islands) :range (mapv island-color islands) :legend true}
                     :marks [(.rectY Plot (clj->js rows) #js {:x "year" :interval 1 :y "n" :fill "label"
                                                               :order (clj->js (mapv lab islands)) :inset 0.5 :tip true})
                             (.ruleX Plot (clj->js events) #js {:x "year" :stroke muted :strokeDasharray "2,3"})
                             (.text Plot (clj->js events) #js {:x "year" :y ymax :text (name lang) :textAnchor "start"
                                                                :dx 4 :dy -8 :fill muted :fontSize 12 :lineWidth 9})
                             (.ruleY Plot #js [0] #js {:stroke ink})]}))
           (data-table lang [[(tx lang :n) :n] ["" :label] ["" :year]] (sort-by :year by-year-island)))))

(defn piramide [lang {:keys [pyramid]}]
  (let [rows (map #(assoc % :sexo (tx lang (if (= "m" (:sex %)) :hombres :mujeres))
                          :v (if (= "m" (:sex %)) (- (:n %)) (:n %))
                          :edad (str (:age %) "–" (+ 4 (:age %))))
                  pyramid)]
    (panel lang "piramide" :piramide-q :piramide-t
           (chart (str "piramide-" (name lang))
                  (fn [w]
                    {:height 380 :marginLeft 56
                     :x {:label (str "← " (tx lang :hombres) " · " (tx lang :mujeres) " →") :labelAnchor "center"
                         :domain (let [m (apply max (map :n pyramid))] [(- m) m])
                         :tickFormat (fn [d] (js/Math.abs d)) :grid true}
                     :y {:label nil :reverse true :domain (clj->js (map #(str % "–" (+ 4 %)) (range 0 90 5)))}
                     :color {:domain [(tx lang :hombres) (tx lang :mujeres)] :range [(serie 0) (serie 1)] :legend true}
                     :marks [(.barX Plot (clj->js rows) #js {:x "v" :y "edad" :fill "sexo" :inset 0.5 :rx 2
                                                              :tip #js {:format #js {:x (fn [d] (js/Math.abs d))}}})
                             (.ruleX Plot #js [0] #js {:stroke ink})]}))
           (data-table lang [["" :sexo] ["" :edad] [(tx lang :n) :n]] rows))))

(defn firma [lang {:keys [literacy]}]
  (let [rows (->> literacy
                  (group-by (juxt :decade :sex))
                  (map (fn [[[d sx] xs]]
                         (let [tot (reduce + (map :n xs))
                               yes (reduce + (map :n (filter :signed xs)))]
                           {:decade d :sexo (tx lang (if (= "m" sx) :hombres :mujeres))
                            :pct (js/Math.round (* 100 (/ yes tot))) :n tot})))
                  (filter #(>= (:n %) 30))
                  (sort-by :decade))]
    (panel lang "firma" :firma-q :firma-t
           (chart (str "firma-" (name lang))
                  (fn [w]
                    {:height 300
                     :x {:label nil :tickFormat (fn [d] (str d "s"))}
                     :y {:label (tx lang :pct) :domain [0 50] :grid true}
                     :color {:domain [(tx lang :hombres) (tx lang :mujeres)] :range [(serie 0) (serie 1)] :legend true}
                     :marks [(.lineY Plot (clj->js rows) #js {:x "decade" :y "pct" :stroke "sexo" :strokeWidth 2})
                             (.dot Plot (clj->js rows) #js {:x "decade" :y "pct" :fill "sexo" :r 4 :stroke "#EEF1EF" :strokeWidth 2 :tip true})
                             (.ruleY Plot #js [0] #js {:stroke ink})]}))
           (data-table lang [["" :decade] ["" :sexo] [(tx lang :pct) :pct] [(tx lang :n) :n]] rows))))

(defn destino [lang {:keys [dest-decade]}]
  (let [lab (dest-label lang)
        rows (map #(assoc % :label (lab (:dest %))) dest-decade)]
    (panel lang "destino" :destino-q :destino-t
           (chart (str "destino-" (name lang))
                  (fn [w]
                    {:height 300
                     :x {:label nil :tickFormat (fn [d] (str d "s"))}
                     :y {:label "%" :percent true :grid true}
                     :color {:domain (mapv lab dests) :range (conj serie none-gray) :legend true}
                     :marks [(.barY Plot (clj->js rows) #js {:x "decade" :y "n" :fill "label" :offset "normalize"
                                                              :order (clj->js (mapv lab dests)) :inset 1 :tip true})]}))
           (data-table lang [["" :decade] ["" :label] [(tx lang :n) :n]] (sort-by :decade rows)))))

(defn reclamo [lang {:keys [reclamo]}]
  (let [rows (sort-by :n > reclamo)]
    (panel lang "reclamo" :reclamo-q :reclamo-t
           (chart (str "reclamo-" (name lang))
                  (fn [w]
                    {:height (+ 40 (* 26 (count rows))) :marginLeft 90
                     :x {:label (tx lang :n) :grid true}
                     :y {:label nil :domain (clj->js (map :kin rows))}
                     :marks [(.barX Plot (clj->js rows) #js {:x "n" :y "kin" :fill (serie 0) :rx 2 :tip true})
                             (.text Plot (clj->js rows) #js {:x "n" :y "kin" :text "n" :dx 6 :textAnchor "start" :fill ink})
                             (.ruleX Plot #js [0] #js {:stroke ink})]}))
           (data-table lang [["" :kin] [(tx lang :n) :n]] rows))))

(defn origen [lang {:keys [origins]}]
  (let [lab (island-label lang)
        rows (map #(assoc % :isla (lab (if (#{"tenerife" "gran-canaria" "la-palma"} (:island %)) (:island %) "otras")))
                  origins)]
    (panel lang "origen" :origen-q :origen-t
           (chart (str "origen-" (name lang))
                  (fn [w]
                    {:height (+ 50 (* 22 (count rows))) :marginLeft 190
                     :x {:label (tx lang :n) :grid true}
                     :y {:label nil :domain (clj->js (map :name rows))}
                     :color {:domain (mapv lab (take 4 islands)) :range serie :legend true}
                     :marks [(.barX Plot (clj->js rows) #js {:x "n" :y "name" :fill "isla" :rx 2 :tip true})
                             (.ruleX Plot #js [0] #js {:stroke ink})]}))
           (data-table lang [["" :name] ["" :isla] [(tx lang :n) :n]] rows))))

(defn grupo [lang {:keys [group]}]
  (let [rows (map #(assoc % :tam (if (= 8 (:size %)) "8+" (str (:size %)))) (sort-by :size (filter #(pos? (:size %)) group)))]
    (panel lang "grupo" :grupo-q :grupo-t
           (chart (str "grupo-" (name lang))
                  (fn [w]
                    {:height 260
                     :x {:label nil :domain (clj->js (map :tam rows))}
                     :y {:label (tx lang :n) :grid true :type "sqrt"}
                     :marks [(.barY Plot (clj->js rows) #js {:x "tam" :y "n" :fill (serie 0) :rx 2 :tip true})
                             (.ruleY Plot #js [0] #js {:stroke ink})]}))
           (data-table lang [["" :tam] [(tx lang :n) :n]] rows))))

(defn barcos [lang {:keys [ships]}]
  (let [rows (map #(assoc % :anios (let [ys (:years %)] (if (= (first ys) (last ys)) (str (first ys)) (str (first ys) "–" (last ys)))))
                  ships)]
    (panel lang "barcos" :barcos-q :barcos-t
           (chart (str "barcos-" (name lang))
                  (fn [w]
                    {:height (+ 50 (* 24 (count rows))) :marginLeft 150 :marginRight 90
                     :x {:label (tx lang :n) :grid true}
                     :y {:label nil :domain (clj->js (map :ship rows))}
                     :marks [(.barX Plot (clj->js rows) #js {:x "n" :y "ship" :fill (serie 2) :rx 2 :tip true})
                             (.text Plot (clj->js rows) #js {:x "n" :y "ship" :text "anios" :dx 6 :textAnchor "start" :fill muted})
                             (.ruleX Plot #js [0] #js {:stroke ink})]}))
           (data-table lang [["" :ship] ["" :anios] [(tx lang :n) :n]] rows))))

(defn letras [lang {:keys [letters]}]
  (let [by (into {} (map (juxt :letter :n)) letters)
        rows (for [c "ABCDEFGHIJKLMNOPQRSTUVWXYZ"] {:letter (str c) :n (get by (str c) 0)})]
    (panel lang "letras" :letras-q :letras-t
           (chart (str "letras-" (name lang))
                  (fn [w]
                    {:height 260
                     :x {:label nil}
                     :y {:label (tx lang :n) :grid true}
                     :marks [(.barY Plot (clj->js rows) #js {:x "letter" :y "n" :fill (serie 0) :rx 2 :tip true})
                             (.ruleY Plot #js [0] #js {:stroke ink})
                             (.text Plot #js [#js {:letter "W" :n 500 :t (if (= lang :en) "T–Z: not in the compilation" "T–Z: no están en la compilación")}]
                                    #js {:x "letter" :y "n" :text "t" :fill (serie 1) :fontSize 13 :lineWidth 8})]}))
           (data-table lang [["" :letter] [(tx lang :n) :n]] rows))))

;; ---------------------------------------------------------------------------

(defonce stats (atom nil))

(defn- load-stats! [sig]
  (-> (js/fetch (str state/base-path "/data/stats.json"))
      (.then #(.text %))
      (.then (fn [txt] (state/with-ctx #(reset! sig (t/read (t/reader :json) txt)))))))

(defonce stats-signal (state/with-ctx #(org.replikativ.spindel.core/signal nil)))

(defn datos-spin []
  (when-not @stats (reset! stats :loading) (load-stats! stats-signal))
  (spin
    (let [lang (iv/get-new (track state/lang-signal))
          st (iv/get-new (track stats-signal))]
      (div {:class "pagina datos"}
        (h1 (tx lang :titulo))
        (p {:class "intro"} (tx lang :intro))
        (when st
          (div {:class "paneles"}
            (por-anio lang st)
            (piramide lang st)
            (destino lang st)
            (firma lang st)
            (reclamo lang st)
            (origen lang st)
            (grupo lang st)
            (barcos lang st)
            (letras lang st)))))))
