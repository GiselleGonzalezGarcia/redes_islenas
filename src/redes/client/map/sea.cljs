(ns redes.client.map.sea
  "The Atlantic map: MapLibre basemap (static GeoJSON, no tiles) with a deck.gl
  overlay of sailing routes, ships (named voyages) and emigrants without a
  named ship, animated over time. Time unit: days since 1 Jan 1840."
  (:require ["maplibre-gl" :as maplibre]
            ["@deck.gl/mapbox" :refer [MapboxOverlay]]
            ["@deck.gl/geo-layers" :refer [TripsLayer]]
            ["@deck.gl/layers" :refer [PathLayer ScatterplotLayer IconLayer TextLayer]]
            [cognitect.transit :as t]
            [redes.client.map.routes :as routes]))

(def epoch-year 1840)
(defn year->day [y] (* (- y epoch-year) 365.25))
(defn day->year [d] (+ epoch-year (/ d 365.25)))

(def colors
  {:mar "#0F3B57" :tierra "#21475D" :costa "#5E8399" :reticula "rgba(238,241,239,0.07)"
   :ruta [238 241 239 26] :farol [226 178 60] :gente [238 241 239] :puerto [238 241 239]})

;; ---------------------------------------------------------------------------
;; Data model

(defn- hash01
  "Deterministic pseudo-random in [0,1) from a string."
  [s]
  (let [h (reduce (fn [h c] (bit-or 0 (+ (* h 31) (.charCodeAt c 0)))) 7 (str s))]
    (/ (mod (js/Math.abs h) 10007) 10007)))

(defn- duration [year] (if (< year 1880) 40 28))   ; days; illustrative sail vs. steam

(def land-days
  "Days an emigrant takes from the home village to the port (illustrative)."
  8)

(defn build-model
  "From flows.json data to trips (named ships), particles (emigrants: from the
  home village to the port, then — without a named ship — across the ocean;
  with a named ship they board it) and villages."
  [{:keys [voyages groups ports villages]}]
  (let [port-coord (into {} (map (fn [[id {:keys [coord]}]] [id coord])) ports)
        trips (->> voyages
                   (keep (fn [{:keys [ship year from to n archive origins]}]
                           (when-let [r (routes/route-cache from to)]
                             (let [t0 (+ (year->day year) (* 330 (hash01 (str ship year archive))))
                                   dur (duration year)]
                               {:ship ship :year year :n n :to to :route r :t0 t0 :dur dur :from from
                                :origins origins
                                :path (clj->js (:path r))
                                :timestamps (clj->js (mapv #(+ t0 (* % dur)) (:frac r)))}))))
                   (sort-by :t0) vec)
        jitter (fn [k] [(- (* 1.2 (hash01 (str k "x"))) 0.6) (- (* 1.2 (hash01 (str k "y"))) 0.6)])
        ;; passengers of named ships walk to the port and board at t0
        boarding (for [{:keys [t0 route from origins ship year]} trips
                       [x y n] origins
                       i (range n)
                       :let [k (str ship year x y i)
                             lag (* 2 (hash01 (str k "l")))]]
                   {:route route :origin [x y] :port (port-coord from)
                    :ts (- t0 land-days lag) :t0 (- t0 lag) :te (- t0 lag) :board true})
        sailing (for [{:keys [year from to n archive origin]} groups
                      :let [r (routes/route-cache from to)]
                      :when r
                      i (range n)
                      :let [k (str year archive to origin i)
                            t0 (+ (year->day year) (* 340 (hash01 k)))
                            dur (* (duration year) (+ 0.9 (* 0.2 (hash01 (str k "d")))))
                            [dx dy] (jitter k)]]
                  {:route r :origin origin :port (port-coord from)
                   :ts (if origin (- t0 land-days) t0) :t0 t0 :te (+ t0 dur) :dur dur :dx dx :dy dy})
        particles (vec (sort-by :ts (concat boarding sailing)))]
    {:trips trips
     :particles particles
     :villages villages
     :routes (->> (concat trips particles) (map :route) distinct (mapv #(clj->js (:path %))))
     :ports (->> ports
                 (keep (fn [[id {:keys [name coord]}]] (when coord {:id id :name name :coord (clj->js coord)})))
                 vec)
     :max-dur 45
     :max-span (+ 45 land-days 2)}))

(defn- with-js
  "Convert the static layer data to JS once."
  [m]
  (assoc m
         :trips-js (into-array (map (fn [{:keys [path timestamps n]}] #js {:path path :timestamps timestamps :n n})
                                    (:trips m)))
         :routes-js (into-array (:routes m))
         :ports-js (into-array (map (fn [{:keys [name coord]}] #js {:name name :coord coord}) (:ports m)))
         :villages-js (into-array (map (fn [{:keys [name coord n]}] #js {:name name :coord (clj->js coord) :n n}) (:villages m)))
         ;; labels: arrival ports, plus one label for the archipelago
         :labels-js (into-array
                     (cons #js {:name "Islas Canarias" :coord #js [-16.3 29.25] :anchor "middle"}
                           (keep (fn [{:keys [id name coord]}]
                                   (when-not (#{"port-sc-tenerife" "port-las-palmas" "port-sc-la-palma"} id)
                                     #js {:name name :coord coord
                                          :anchor (if (= id "port-buenos-aires") "end" "start")}))
                                 (:ports m))))))

(defn- active
  "Items of a sorted vector (by start key ks) under way at time t (start ≤ t < end)."
  [items t span ks ke]
  (let [n (count items)
        start (loop [lo 0 hi n]                       ; first index with start > t - span
                (if (< lo hi)
                  (let [m (quot (+ lo hi) 2)]
                    (if (<= (ks (items m)) (- t span)) (recur (inc m) hi) (recur lo m)))
                  lo))]
    (loop [i start acc (transient [])]
      (if (and (< i n) (<= (ks (items i)) t))
        (let [it (items i)]
          (recur (inc i) (if (< t (ke it)) (conj! acc it) acc)))
        (persistent! acc)))))

(defn- ease [u] (* u u (- 3 (* 2 u))))

(defn at-sea
  "State at time t: ships under sail, people dots, counts at sea and on the way to port."
  [{:keys [trips particles max-dur max-span]} t]
  (let [ships (mapv (fn [tr]
                      (let [[pos ang] (routes/position (:route tr) (/ (- t (:t0 tr)) (:dur tr)))]
                        (assoc tr :pos (clj->js pos) :angle ang)))
                    (active trips t max-dur :t0 #(+ (:t0 %) (:dur %))))
        ps (active particles t max-span :ts :te)
        to-port (filter #(< t (:t0 %)) ps)
        sailing (remove #(< t (:t0 %)) ps)
        land (mapv (fn [{:keys [origin port ts t0]}]
                     (let [u (ease (min 1 (/ (- t ts) (max 1 (- t0 ts)))))
                           [x0 y0] origin [x1 y1] port]
                       #js [(+ x0 (* u (- x1 x0))) (+ y0 (* u (- y1 y0)))]))
                   (filter :origin to-port))
        sea (mapv (fn [p]
                    (let [f (/ (- t (:t0 p)) (:dur p))
                          [[x y]] (routes/position (:route p) f)
                          s (js/Math.sin (* js/Math.PI f))]
                      #js [(+ x (* s (:dx p))) (+ y (* s (:dy p)))]))
                  sailing)]
    {:ships ships
     :people sea
     :walking land
     :count (+ (count sea) (reduce + (map :n ships)))
     :to-port (count to-port)}))

;; ---------------------------------------------------------------------------
;; Layers

(def ship-icon
  (str "data:image/svg+xml;charset=utf-8,"
       (js/encodeURIComponent
        "<svg xmlns='http://www.w3.org/2000/svg' width='64' height='64' viewBox='0 0 64 64'><path d='M8 40h48l-7 10H15z' fill='#E2B23C'/><path d='M30 8v30H12z' fill='#EEF1EF'/><path d='M34 12v26h16z' fill='#EEF1EF' opacity='.85'/><path d='M32 6v34' stroke='#E2B23C' stroke-width='2'/></svg>")))

(defn layers [model t {:keys [labels? show on-pick zoom village-labels] :or {show #{:routes :ships :people :villages}}}]
  (let [{:keys [ships people walking]} (at-sea model t)]
    (into-array
     (cond->
      [(PathLayer. #js {:id "rutas" :data (:routes-js model) :getPath identity :visible (contains? show :routes)
                        :getColor (clj->js (:ruta colors)) :widthUnits "pixels" :getWidth 1})
       (TripsLayer. #js {:id "estelas" :data (:trips-js model)
                         :getPath (fn [d] (.-path d)) :getTimestamps (fn [d] (.-timestamps d))
                         :getColor (clj->js (:farol colors))
                         :getWidth (fn [d] (+ 1.5 (js/Math.sqrt (.-n d))))
                         :widthUnits "pixels" :capRounded true :jointRounded true
                         :fadeTrail true :trailLength 30 :currentTime t :visible (contains? show :ships)})
       (ScatterplotLayer. #js {:id "pueblos" :data (:villages-js model) :getPosition (fn [d] (.-coord d))
                               :visible (contains? show :villages)
                               :getRadius (fn [d] (* 260 (js/Math.sqrt (.-n d)))) :radiusUnits "meters"
                               :radiusMinPixels 1.5 :radiusMaxPixels 40
                               :getFillColor (clj->js (conj (:gente colors) 28))
                               :getLineColor (clj->js (conj (:gente colors) 110)) :stroked true
                               :lineWidthUnits "pixels" :getLineWidth 1 :pickable true})
       (ScatterplotLayer. #js {:id "caminantes" :data (into-array walking) :getPosition identity
                               :getFillColor (clj->js (conj (:farol colors) 200))
                               :radiusUnits "pixels" :getRadius 1.8 :visible (contains? show :people)})
       (ScatterplotLayer. #js {:id "gente" :data (into-array people) :getPosition identity
                               :getFillColor (clj->js (conj (:gente colors) 150))
                               :radiusUnits "pixels" :getRadius 1.6 :visible (contains? show :people)})
       (IconLayer. #js {:id "barcos" :visible (contains? show :ships)
                        :data (into-array (map (fn [{:keys [pos n ship year to]}] #js {:pos pos :n n :ship ship :year year :to to}) ships))
                        :onClick (fn [info] (when (and on-pick (.-object info)) (on-pick (.-object info))) true)
                        :getPosition (fn [d] (.-pos d))
                        :getIcon (fn [_] #js {:url ship-icon :width 64 :height 64 :anchorY 48})
                        :getSize (fn [d] (+ 18 (* 3 (js/Math.sqrt (.-n d)))))
                        :sizeUnits "pixels"
                        :pickable true})
       (ScatterplotLayer. #js {:id "puertos" :data (:ports-js model) :getPosition (fn [d] (.-coord d))
                               :getFillColor (clj->js (conj (:puerto colors) 220)) :radiusUnits "pixels" :getRadius 3})]
       labels?
       (conj (TextLayer. #js {:id "rotulos" :data (:labels-js model) :getPosition (fn [d] (.-coord d))
                              :getText (fn [d] (.-name d)) :characterSet "auto"
                              :fontFamily "Ibarra Real Nova, Georgia, serif" :getSize 15
                              :getColor (clj->js (conj (:puerto colors) 230))
                              :getTextAnchor (fn [d] (.-anchor d))
                              :getPixelOffset (fn [d] (case (.-anchor d) "end" #js [-8 0] "middle" #js [0 -4] #js [8 0]))}))
       (and labels? zoom (>= zoom 6.4))
       (conj (TextLayer. #js {:id "rotulos-pueblos" :data (or village-labels #js [])
                              :getPosition (fn [d] (.-coord d))
                              :getText (fn [d] (.-name d)) :characterSet "auto"
                              :fontFamily "Ibarra Real Nova, Georgia, serif" :getSize 13
                              :getColor (clj->js (conj (:puerto colors) 225))
                              :getTextAnchor "start" :getPixelOffset #js [7 0]}))))))

;; ---------------------------------------------------------------------------
;; Map

(defn- graticule []
  (clj->js
   {:type "FeatureCollection"
    :features (concat
               (for [lon (range -180 181 10)]
                 {:type "Feature" :geometry {:type "LineString" :coordinates (for [lat (range -80 81 5)] [lon lat])}})
               (for [lat (range -80 81 10)]
                 {:type "Feature" :geometry {:type "LineString" :coordinates (for [lon (range -180 181 5)] [lon lat])}}))}))

(defn- style [base]
  (clj->js
   {:version 8
    :sources {:tierra {:type "geojson" :data (str base "/geo/mundo.geojson")}
              :canarias {:type "geojson" :data (str base "/geo/canarias.geojson")}
              :reticula {:type "geojson" :data (graticule)}}
    :layers [{:id "mar" :type "background" :paint {:background-color (:mar colors)}}
             {:id "reticula" :type "line" :source "reticula" :paint {:line-color (:reticula colors) :line-width 1}}
             {:id "tierra" :type "fill" :source "tierra" :paint {:fill-color (:tierra colors)}}
             {:id "costa" :type "line" :source "tierra" :paint {:line-color (:costa colors) :line-width 0.6}}
             {:id "canarias" :type "fill" :source "canarias" :paint {:fill-color (:tierra colors)}}
             {:id "canarias-costa" :type "line" :source "canarias" :paint {:line-color (:costa colors) :line-width 0.8}}]}))

(defn fetch-flows [base]
  (-> (js/fetch (str base "/data/flows.json"))
      (.then #(.text %))
      (.then #(with-js (build-model (t/read (t/reader :json) %))))))

(def declutter
  "Village labels that fit without overlapping at the current view (largest first).
  Cached per view (center + zoom)."
  (let [cache (atom nil)]
    (fn [^js m model]
      (let [k [(.toString (.getCenter m)) (.getZoom m)]]
        (if (= k (first @cache))
          (second @cache)
          (let [placed (atom [])
                out (->> (sort-by #(- (.-n %)) (filter #(>= (.-n %) 3) (:villages-js model)))
                         (filter (fn [d]
                                   (let [^js pt (.project m (.-coord d))
                                         x0 (+ (.-x pt) 5) y0 (- (.-y pt) 9)
                                         x1 (+ x0 (* 6.6 (count (.-name d)))) y1 (+ y0 18)
                                         clash? (some (fn [[a b c e]] (and (< x0 c) (< a x1) (< y0 e) (< b y1))) @placed)]
                                     (when-not clash? (swap! placed conj [x0 y0 x1 y1]) (not clash?)))))
                         into-array)]
            (reset! cache [k out])
            out))))))

(defn create!
  "Mount a map in el. opts: :interactive? :bounds [[w s] [e n]] :labels?
  Returns {:map :overlay :render! (fn [t])}."
  [el base model {:keys [interactive? bounds labels? padding] :as opts}]
  (let [m (maplibre/Map. (clj->js {:container el
                                   :style (style base)
                                   :bounds (or bounds [[-85 8] [-13 32]])
                                   :fitBoundsOptions {:padding (or padding 20)}
                                   :interactive (boolean interactive?)
                                   :attributionControl {:compact true
                                                        :customAttribution "Costas: Natural Earth"}
                                   :renderWorldCopies false}))
        overlay (MapboxOverlay. #js {:interleaved false :layers #js []})]
    (.addControl m overlay)
    (let [ro (js/ResizeObserver. (fn [_] (.resize m)))]
      (.observe ro el)
      (.on m "remove" #(.disconnect ro)))
    {:map m
     :overlay overlay
     :render! (fn [t] (.setProps overlay #js {:layers (layers model t (assoc (if-let [a (:opts-atom opts)] (merge opts @a) opts)
                                                                         :zoom (.getZoom m)
                                                                         :village-labels (declutter m model)))}))}))

(defn animate!
  "Play from year y0 to y1 at `days-per-sec`, looping. on-frame gets (t, at-sea-count).
  Returns a stop fn. Pauses while the tab is hidden."
  [{:keys [render!]} model {:keys [y0 y1 days-per-sec on-frame]}]
  (let [d0 (year->day y0) d1 (year->day y1)
        state (atom {:t d0 :last nil :raf nil :stopped false})
        step (fn step [now]
               (let [{:keys [t last stopped]} @state]
                 (when-not stopped
                   (let [dt (if last (min 0.1 (/ (- now last) 1000)) 0)
                         t' (let [x (+ t (* dt days-per-sec))] (if (> x d1) d0 x))]
                     (render! t')
                     (when on-frame (on-frame t' (:count (at-sea model t'))))
                     (swap! state assoc :t t' :last now :raf (js/requestAnimationFrame step))))))]
    (swap! state assoc :raf (js/requestAnimationFrame step))
    (fn [] (swap! state assoc :stopped true) (some-> (:raf @state) js/cancelAnimationFrame))))

(defn player
  "Controllable playback. Returns {:play! :pause! :toggle! :seek! :speed! :state}.
  on-frame gets (t at-sea) on every rendered frame."
  [{:keys [render!]} model {:keys [y0 y1 t days-per-sec on-frame]}]
  (let [d0 (year->day y0) d1 (year->day y1)
        st (atom {:t (or t d0) :playing false :speed (or days-per-sec 60) :last nil :raf nil})
        draw! (fn [t] (render! t) (when on-frame (on-frame t (at-sea model t))))
        step (fn step [now]
               (let [{:keys [t last playing speed]} @st]
                 (when playing
                   (let [dt (if last (min 0.1 (/ (- now last) 1000)) 0)
                         t' (let [x (+ t (* dt speed))] (if (> x d1) d0 x))]
                     (draw! t')
                     (swap! st assoc :t t' :last now :raf (js/requestAnimationFrame step))))))
        play! (fn [] (when-not (:playing @st)
                       (swap! st assoc :playing true :last nil :raf (js/requestAnimationFrame step))))
        pause! (fn [] (swap! st assoc :playing false) (some-> (:raf @st) js/cancelAnimationFrame))]
    (draw! (:t @st))
    {:state st
     :play! play!
     :pause! pause!
     :toggle! (fn [] (if (:playing @st) (pause!) (play!)))
     :seek! (fn [t] (swap! st assoc :t (max d0 (min d1 t))) (draw! (:t @st)))
     :speed! (fn [v] (swap! st assoc :speed v))
     :redraw! (fn [] (draw! (:t @st)))}))
