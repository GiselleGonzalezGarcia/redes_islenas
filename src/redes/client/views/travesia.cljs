(ns redes.client.views.travesia
  "Interactive crossing map: timeline, playback, layer toggles, ships at sea."
  (:require [org.replikativ.spindel.core :as s :refer-macros [spin]]
            [org.replikativ.spindel.effects.track :refer [track]]
            [org.replikativ.spindel.incremental.interval :as iv]
            [org.replikativ.spindel.dom.elements :as el :refer-macros [div]]
            [org.replikativ.spindel.dom.foreign :refer-macros [foreign-node]]
            [clojure.string :as str]
            [redes.client.map.sea :as sea]
            [redes.client.state :as state]
            [redes.client.i18n :refer [t]]))

(defn- h
  "Tiny DOM builder for the imperative widget: (h :tag {attrs} & children)."
  [tag attrs & children]
  (let [e (js/document.createElement (name tag))]
    (doseq [[k v] attrs :when (some? v)]
      (if (str/starts-with? (name k) "on-")
        (.addEventListener e (subs (name k) 3) v)
        (.setAttribute e (name k) v)))
    (doseq [c children :when c]
      (.append e (if (string? c) (js/document.createTextNode c) c)))
    e))

(def speeds {:lenta 30 :normal 90 :rapida 240})
(def y0 1840)
(def y1 1900)

(defonce widget (atom nil))

(defn- ship-list [lang ports ships]
  (if (empty? ships)
    [(h :p {:class "vacio"} (t lang :tr/nadie))]
    (for [{:keys [ship n year to]} (sort-by :n > ships)]
      (h :li {}
         (h :b {} ship)
         (h :span {} (str n " " (t lang :tr/pasajeros) " · " (t lang :tr/salida) " " year
                          (when-let [p (some-> (get ports to) :name)]
                            (str " → " p))))))))

(defn- param [k] (.get (js/URLSearchParams. js/location.search) k))

(defn- mount! [lang el]
  (-> (sea/fetch-flows state/base-path)
      (.then
       (fn [model]
         (when (.-isConnected el)
           (let [map-el (let [e (h :div {:class "mapa"})] (.append el e) e)   ; sized before the map is created
                 opts (atom {:show #{:routes :ships :people :villages}})
                 m (sea/create! map-el state/base-path model {:interactive? true :labels? true :opts-atom opts
                                                              :bounds (if (param "islas") [[-18.3 27.55] [-13.3 29.45]] [[-88 6] [-13 33]])
                                                              :padding (if (> (.-clientWidth el) 900)
                                                                         {:left 30 :right 340 :top 30 :bottom 230}
                                                                         {:left 10 :right 10 :top 10 :bottom 260})})
                 year-out (h :output {:class "anio-grande"} "1840")
                 slider (h :input {:type "range" :min (str (sea/year->day y0)) :max (str (sea/year->day y1))
                                   :step "1" :value "0" :aria-label (t lang :tr/anio)})
                 play-btn (h :button {:class "boton" :type "button"} (t lang :tr/play))
                 list-el (h :ul {:class "lista-barcos"})
                 count-el (h :span {:class "cuenta-mar"})
                 last-sig (atom nil)
                 port-names (into {} (map (juxt :id identity)) (:ports model))
                 on-frame (fn [tt {:keys [ships count]}]
                            (set! (.-textContent year-out) (js/Math.floor (sea/day->year tt)))
                            (set! (.-value slider) (str (js/Math.round tt)))
                            (set! (.-textContent count-el) (str (.toLocaleString count "es-ES")))
                            (let [sig (mapv :ship ships)]
                              (when (not= sig @last-sig)
                                (reset! last-sig sig)
                                (.replaceChildren list-el)
                                (doseq [li (ship-list lang port-names ships)] (.append list-el li)))))
                 p (atom nil)
                 toggle (fn [k label]
                          (let [cb (h :input {:type "checkbox" :checked "checked"
                                              :on-change (fn [e]
                                                           (swap! opts update :show (if (.. e -target -checked) conj disj) k)
                                                           ((:redraw! @p)))})]
                            (h :label {:class "conmuta"} cb (str " " label))))
                 islands? (atom (boolean (param "islas")))
                 zoom-btn (h :button {:class "boton-sec" :type "button"
                                      :on-click (fn [e]
                                                  (swap! islands? not)
                                                  (set! (.. e -target -textContent) (t lang (if @islands? :tr/atlantico :tr/islas)))
                                                  (.fitBounds (:map m) (clj->js (if @islands? [[-18.3 27.55] [-13.3 29.45]] [[-88 6] [-13 33]]))
                                                              (clj->js {:padding (if (> (.-clientWidth el) 900)
                                                                                   {:left 30 :right 340 :top 30 :bottom 230}
                                                                                   {:left 10 :right 10 :top 10 :bottom 260})
                                                                        :duration 1500})))}
                             (t lang (if @islands? :tr/atlantico :tr/islas)))
                 speed-sel (h :select {:aria-label (t lang :tr/velocidad)
                                       :on-change (fn [e] ((:speed! @p) (speeds (keyword (.. e -target -value)))))}
                              (h :option {:value "lenta"} (t lang :tr/lenta))
                              (h :option {:value "normal" :selected "selected"} (t lang :tr/normal))
                              (h :option {:value "rapida"} (t lang :tr/rapida)))]
             (.append el
                      (h :div {:class "mandos"}
                         (h :div {:class "fila"} play-btn year-out
                            (h :label {:class "vel"} (str (t lang :tr/velocidad) " ") speed-sel))
                         (h :div {:class "fila"} zoom-btn)
                         slider
                         (h :div {:class "fila conmutas"}
                            (toggle :ships (t lang :tr/barcos))
                            (toggle :people (t lang :tr/personas))
                            (toggle :villages (t lang :tr/pueblos))
                            (toggle :routes (t lang :tr/rutas)))
                         (h :p {:class "nota"} (t lang :tr/nota)))
                      (h :aside {:class "a-bordo"}
                         (h :h2 {} (t lang :tr/en-mar) " " count-el)
                         list-el))
             (.on (:map m) "load"
                  (fn []
                    (reset! p (sea/player m model {:y0 y0 :y1 y1 :t (sea/year->day (or (some-> (param "anio") js/parseFloat) 1853.3))
                                                   :days-per-sec (:normal speeds) :on-frame on-frame}))
                    (.addEventListener play-btn "click"
                                       (fn [_] ((:toggle! @p))
                                         (set! (.-textContent play-btn)
                                               (t lang (if (:playing @(:state @p)) :tr/pausa :tr/play)))))
                    (.addEventListener slider "input"
                                       (fn [e] ((:seek! @p) (js/parseFloat (.. e -target -value)))))))
             (reset! widget {:map m :player p})))))))

(defn- unmount! [_]
  (when-let [{:keys [map player]} @widget]
    (when-let [pause! (some-> player deref :pause!)] (pause!))
    (some-> map :map .remove)
    (reset! widget nil)))

(defn travesia-spin []
  (spin
    (let [lang (iv/get-new (track state/lang-signal))]
      (div {:class "travesia"}
        (foreign-node {:key (name lang) :class "travesia-lienzo"
                       :on-mount #(mount! lang %) :on-unmount unmount!})))))
