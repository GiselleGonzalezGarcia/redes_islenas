(ns redes.client.views.inicio
  "Home: sea hero with search, key figures."
  (:require [org.replikativ.spindel.core :as s :refer-macros [spin]]
            [org.replikativ.spindel.effects.track :refer [track]]
            [org.replikativ.spindel.incremental.interval :as iv]
            [org.replikativ.spindel.dom.elements :as el
             :refer-macros [div span h1 p input button form section b]]
            [org.replikativ.spindel.dom.foreign :refer-macros [foreign-node]]
            [redes.client.map.sea :as sea]
            [redes.client.state :as state]
            [redes.client.i18n :refer [t]]
            [redes.client.views.common :as c]))

(defonce hero-map (atom nil))

(defn- reduced-motion? []
  (.-matches (js/window.matchMedia "(prefers-reduced-motion: reduce)")))

(defn- mount-sea! [el]
  (-> (sea/fetch-flows state/base-path)
      (.then (fn [model]
               (when (.-isConnected el)
                 (let [m (sea/create! el state/base-path model
                                     {:interactive? false
                                      ;; keep the destinations clear of the title column on wide screens
                                      :padding (if (> (.-clientWidth el) 900)
                                                 {:left (min 560 (* 0.42 (.-clientWidth el))) :right 30 :top 40 :bottom 60}
                                                 20)})
                       hero (.closest el ".hero")
                       year-el (.querySelector hero ".anio b")
                       count-el (.querySelector hero ".anio span")
                       show! (fn [t n]
                               (set! (.-textContent year-el) (js/Math.floor (sea/day->year t)))
                               (set! (.-textContent count-el) (.toLocaleString n "es-ES")))]
                   (.on (:map m) "load"
                        (fn []
                          (if-let [y (or (some-> (.get (js/URLSearchParams. js/location.search) "anio") js/parseFloat
                                                 (#(when-not (js/isNaN %) %)))
                                         (when (reduced-motion?) 1891.4))]
                            (let [t (sea/year->day y)]
                              ((:render! m) t)
                              (show! t (:count (sea/at-sea model t))))
                            (reset! hero-map
                                    (assoc m :stop (sea/animate! m model {:y0 1846 :y1 1897 :days-per-sec 110
                                                                          :on-frame show!}))))))
                   (reset! hero-map (merge @hero-map m))))))))

(defn- unmount-sea! [_]
  (when-let [{:keys [stop map]} @hero-map]
    (when stop (stop))
    (when map (.remove map))
    (reset! hero-map nil)))

(defn hero [lang]
  (section {:class "hero"}
    (foreign-node {:class "mapa-mar" :on-mount mount-sea! :on-unmount unmount-sea!})
    (div {:class "anio" :aria-hidden "true"} (b "1846") (span ""))
    (div {:class "rotulo"}
      (h1 "Redes Isleñas")
      (p {:class "entrada"} (t lang :hero/entrada))
      (form {:class "busca-hero" :role "search"
             :on-submit (fn [e]
                          (.preventDefault e)
                          (state/set-search! (.. e -target -elements -q -value))
                          (state/navigate! :explorar {}))}
        (input {:type "search" :name "q" :aria-label (t lang :busca/placeholder)
                :placeholder (t lang :busca/placeholder)})
        (button {:class "boton" :type "submit"} (t lang :busca/boton))))))

(defn cifra [k n label]
  (div {:class "cifra" :key k} (b n) (span label)))

(defn inicio-spin []
  (spin
    (let [lang (iv/get-new (track state/lang-signal))
          index (iv/get-new (track state/index-signal))]
      (div
        (hero lang)
        (div {:class "pagina"}
          (when index
            (div {:class "cifras"}
              (cifra "p" (c/fmt-n (:n-persons index)) (t lang :cifra/personas))
              (cifra "o" (c/fmt-n (:n-origins index)) (t lang :cifra/pueblos))
              (cifra "v" (c/fmt-n (:n-voyages index)) (t lang :cifra/barcos))
              (cifra "a" (str (first (:years index)) "–" (second (:years index))) (t lang :cifra/anios)))))))))
